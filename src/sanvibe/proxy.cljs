(ns sanvibe.proxy
  "A connection to the mesh through a dimmer that acts as GATT proxy. Over it
  we take part in the mesh as a node of our own and exchange access messages
  with the dimmers."
  (:require ["@stoprocent/noble$default" :as noble]
            [promesa.core :as p]
            [sanvibe.config :as config]
            [sanvibe.log :as log]
            [sanvibe.mesh :as mesh]))

(def ^:private unreachable
  "Can't reach the dimmer. Is it in Bluetooth range, and the SANlight app closed?")

(defn- scan
  "Scans for a dimmer that advertises the mesh proxy service for our network."
  [network-id]
  (p/create
    (fn [resolve reject]
      (let [timer (js/setTimeout #(reject (ex-info unreachable {})) 45000)
            ours? (fn [service-data]
                    (and (= "1828" (.-uuid service-data))
                         (= 0 (aget (.-data service-data) 0))
                         (.equals network-id (.subarray (.-data service-data) 1 9))))]
        (.on noble "discover"
             (fn [peripheral]
               (when (some ours? (.. peripheral -advertisement -serviceData))
                 (js/clearTimeout timer)
                 (resolve peripheral))))
        (.catch (.startScanningAsync noble #js ["1828"] true) reject)))))

(defn- connect-peripheral
  "Scanning for the dimmer is slow and unreliable on macOS, connecting to the
  one we know from last time is quicker."
  [network-id state]
  (if-let [id (:peripheral @state)]
    (p/let [_ (log/step (str "Connecting to the dimmer from last time (" (subs id 0 8) ")..."))
            peripheral (p/race [(.connectAsync noble id) (p/delay 30000 nil)])]
      (when-not peripheral
        ;; scan next time, in case it is a different dimmer by now
        (swap! state dissoc :peripheral)
        (config/save-state! state)
        (throw (ex-info unreachable {})))
      peripheral)
    (p/let [_ (log/step "Scanning for a dimmer of this mesh (only on the first run, can take a while)...")
            peripheral (scan network-id)
            _ (.stopScanningAsync noble)
            _ (log/step (str "Found " (.. peripheral -advertisement -localName) " (" (subs (.-id peripheral) 0 8)
                             ", RSSI " (.-rssi peripheral) " dBm), connecting..."))
            _ (.connectAsync peripheral)]
      (swap! state assoc :peripheral (.-id peripheral))
      (config/save-state! state)
      peripheral)))

(defn- wait-for
  "Resolves with the first non-nil result of `f` for an incoming proxy message,
  or with nil after `timeout` ms."
  [{:keys [listeners]} f timeout]
  (let [found (p/deferred)
        listener (fn [message]
                   (when-some [result (f message)]
                     (p/resolve! found result)))]
    (swap! listeners conj listener)
    (p/then (p/race [found (p/delay timeout nil)])
            (fn [result]
              (swap! listeners disj listener)
              result))))

(defn- tx-meta [{:keys [iv state]} ctl dst]
  (let [{:keys [iv-index iv-update?]} @iv]
    ;; during an IV update, messages are still sent with the old index
    {:iv-index (cond-> iv-index iv-update? dec)
     :ctl ctl :ttl 5 :seq (config/next-seq! state) :src (:src @state) :dst dst}))

(defn- write!
  "Writes a network PDU to the proxy. One write at a time: noble only keeps
  the callback of a characteristic's latest write, concurrent ones would never
  complete."
  [{:keys [data-in max-len net-key writes]} meta transport-pdu]
  (let [packets (mesh/proxy-segments :network (mesh/encode-network net-key meta transport-pdu) max-len)
        done (p/then @writes
                     (fn [_]
                       (reduce (fn [chain packet] (p/then chain (fn [_] (.writeAsync data-in packet true))))
                               (p/resolved nil)
                               packets)))]
    ;; a failed write mustn't block the ones after it
    (reset! writes (p/catch done (fn [_] :failed)))
    done))

(defn- ack-segments!
  "Acknowledges the segments of a message that we have. The sender repeats
  the ones that are missing until all are acknowledged."
  [conn src seq-zero seg-os]
  (write! conn (tx-meta conn 1 src) (mesh/segment-ack seq-zero seg-os)))

(defn- receive
  "Takes a network PDU for us and returns {:net :upper}, its header fields and
  the still encrypted upper transport PDU, once an access message is complete,
  or {:net :ack} for an acknowledgment of the segments we sent."
  [{:keys [net-key iv state segments] :as conn} pdu]
  (when-let [iv-index (:iv-index @iv)]
    (when-let [{:keys [ctl src dst seq transport-pdu] :as net} (mesh/decode-network net-key iv-index pdu)]
      (when (= dst (:src @state))
        (let [{:keys [upper segment seq-zero seg-o seg-n szmic]} (when (zero? ctl) (mesh/parse-lower transport-pdu))]
          (cond
            (= 1 ctl)
            (when-let [ack (mesh/parse-segment-ack transport-pdu)]
              {:net net :ack ack})

            upper
            {:net net :upper upper}

            :else
            (let [id [src seq-zero]
                  {:keys [parts timer]} (get (swap! segments assoc-in [id :parts seg-o] segment) id)]
              (some-> timer js/clearTimeout)
              (if (= (count parts) (inc seg-n))
                (do (swap! segments dissoc id)
                    (ack-segments! conn src seq-zero (keys parts))
                    {:net (assoc net :seq (mesh/seq-auth seq seq-zero) :szmic szmic)
                     :upper (apply mesh/bcat (map parts (range (inc seg-n))))})
                ;; the dimmer sends no more than five segments before it waits
                ;; for an acknowledgment, so acknowledge what's there once it pauses
                (do (swap! segments assoc-in [id :timer]
                           (js/setTimeout #(ack-segments! conn src seq-zero (keys parts)) 150))
                    nil)))))))))

(defn connect
  "Connects to the mesh through a dimmer and waits for its secure network
  beacon, which proves that we have the right network key and tells us the
  IV index that messages are encrypted with."
  [{:keys [net-key] :as network}]
  (p/let [state (config/load-state network)
          _ (log/step "Waiting for Bluetooth...")
          _ (p/catch (.waitForPoweredOnAsync noble 10000)
                     (fn [_]
                       (throw (ex-info (str "Bluetooth isn't available (" (.-state noble) "). Is it on, "
                                            "and does this terminal have Bluetooth access?")
                                       {}))))
          peripheral (connect-peripheral (:network-id net-key) state)
          _ (log/step "Connected, discovering the mesh proxy service...")
          found (.discoverSomeServicesAndCharacteristicsAsync peripheral #js ["1828"] #js ["2add" "2ade"])]
    (let [[data-in data-out] (for [uuid ["2add" "2ade"]]
                               (some #(when (= uuid (.-uuid %)) %) (.-characteristics found)))
          conn {:peripheral peripheral
                :data-in data-in
                :max-len (- (or (.-mtu peripheral) 23) 3)
                :listeners (atom #{})
                :writes (atom (p/resolved nil))
                :segments (atom {})
                :net-key net-key
                :iv (atom nil)
                :state state}
          reassembly (atom nil)
          beacon (wait-for conn
                           (fn [{:keys [type pdu]}]
                             (when (= :beacon type)
                               (mesh/parse-beacon net-key pdu)))
                           5000)]
      (.on data-out "data"
           (fn [packet _]
             (let [[acc message] (mesh/proxy-reassemble @reassembly packet)]
               (reset! reassembly acc)
               (when-let [message (if (= :network (:type message))
                                    (receive conn (:pdu message))
                                    message)]
                 (doseq [listener @(:listeners conn)]
                   (listener message))))))
      (p/let [_ (log/step (str "Found it (MTU " (.-mtu peripheral) "), waiting for the network beacon..."))
              _ (.subscribeAsync data-out)
              {:keys [iv-index] :as beacon} beacon]
        (when-not beacon
          (throw (ex-info "The dimmer didn't accept the network key, is the mesh export up to date?" {})))
        (reset! (:iv conn) beacon)
        (log/step (str "Joined the mesh as " (log/address (:src @state)) " (IV index " iv-index ")"))
        conn))))

(defn disconnect [{:keys [peripheral]}]
  (p/let [_ (.disconnectAsync peripheral)]
    (log/step "Disconnected")))

(defn rssi
  "Signal strength of the connection to the proxy dimmer, in dBm."
  [{:keys [peripheral]}]
  (.updateRssiAsync peripheral))

(defn- send-segmented!
  "Sends an access message that is too long for one network PDU in segments,
  repeating those that `dst` doesn't acknowledge."
  [conn key dst payload]
  (let [first-meta (tx-meta conn 0 dst)
        seq-zero (bit-and (:seq first-meta) 0x1fff)
        segments (mesh/encode-access-segments key first-meta payload)
        acked? (fn [block-ack i] (= 1 (bit-and (unsigned-bit-shift-right block-ack i) 1)))
        round (fn round [pending metas attempts]
                (let [ack (wait-for conn
                                    (fn [{:keys [net ack]}]
                                      (when (and ack (= dst (:src net)) (= seq-zero (:seq-zero ack)))
                                        (:block-ack ack)))
                                    1000)]
                  (p/let [_ (p/all (mapv #(write! conn (metas %) (nth segments %)) pending))
                          block-ack ack
                          missing (if block-ack
                                    (remove #(acked? block-ack %) (range (count segments)))
                                    pending)]
                    (cond
                      (empty? missing) nil
                      (> attempts 1) (round missing (fn [_] (tx-meta conn 0 dst)) (dec attempts))
                      :else (throw (ex-info (str (log/address dst) " didn't take the whole message.") {}))))))]
    ;; the first segment goes out with the sequence number of the encryption
    (round (range (count segments)) #(if (zero? %) first-meta (tx-meta conn 0 dst)) 4)))

(defn- send!
  "Sends an access message (opcode + parameters) encrypted with `key` to `dst`."
  [conn key dst payload]
  (if (> (.-length payload) 11)
    (send-segmented! conn key dst payload)
    (let [meta (tx-meta conn 0 dst)]
      (write! conn meta (mesh/encode-access key meta payload)))))

(defn request
  "Sends an access message to `dst` and returns the parameters of its reply
  with `reply-opcode`, retrying a few times before giving up."
  ([conn key dst payload reply-opcode]
   (request conn key dst payload reply-opcode 3))
  ([conn key dst payload reply-opcode attempts]
   (let [reply (wait-for conn
                         (fn [{:keys [net upper]}]
                           (when-let [access (and upper (= dst (:src net))
                                                  (some-> (mesh/decrypt-access key net upper) mesh/parse-access))]
                             (when (= reply-opcode (:opcode access))
                               (:params access))))
                         ;; segmented messages take a while to send
                         (if (> (.-length payload) 11) 6000 1500))]
     (p/let [_ (send! conn key dst payload)
             reply reply]
       (cond
         reply reply
         (> attempts 1) (do (log/step (str "No answer from " (log/address dst) ", trying again..."))
                            (request conn key dst payload reply-opcode (dec attempts)))
         :else (throw (ex-info (str "No answer from " (log/address dst) ".") {})))))))
