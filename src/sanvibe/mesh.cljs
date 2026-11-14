(ns sanvibe.mesh
  "Just enough Bluetooth Mesh (Mesh Profile 1.0) to talk to a node through a
  GATT proxy: key derivation, network + transport layer crypto, proxy PDUs.
  Everything in here is pure; section numbers refer to the Mesh Profile spec."
  (:require ["node:crypto" :as crypto]))

;; --- bytes -----------------------------------------------------------------

(defn hex->buf [s] (js/Buffer.from s "hex"))
(defn buf->hex [b] (.toString b "hex"))
(defn bcat [& bufs] (js/Buffer.concat (into-array bufs)))

(defn- uint [n width]
  (let [b (js/Buffer.alloc width)]
    (.writeUIntBE b n 0 width)
    b))

;; the mesh layers are big endian
(defn u8 [n] (uint n 1))
(defn u16 [n] (uint n 2))
(defn u24 [n] (uint n 3))
(defn u32 [n] (uint n 4))

(defn- ascii [s] (js/Buffer.from s "ascii"))

(defn- bxor [a b]
  (let [out (js/Buffer.alloc (.-length a))]
    (dotimes [i (.-length a)]
      (aset out i (bit-xor (aget a i) (aget b i))))
    out))

;; --- AES primitives --------------------------------------------------------

(defn- aes [algo key iv data]
  (let [c (crypto/createCipheriv algo key iv)]
    (.setAutoPadding c false)
    (bcat (.update c data) (.final c))))

(defn- e [key block] (aes "aes-128-ecb" key nil block))

(defn- dbl
  "Doubling in GF(2^128), used for the CMAC subkeys (RFC 4493)."
  [b]
  (let [out (js/Buffer.alloc 16)]
    (dotimes [i 16]
      (aset out i (bit-and 0xff (bit-or (bit-shift-left (aget b i) 1)
                                        (if (< i 15) (bit-shift-right (aget b (inc i)) 7) 0)))))
    (when (bit-test (aget b 0) 7)
      (aset out 15 (bit-xor (aget out 15) 0x87)))
    out))

(defn cmac
  "AES-CMAC (RFC 4493)."
  [key msg]
  (let [k1 (dbl (e key (js/Buffer.alloc 16)))
        k2 (dbl k1)
        len (.-length msg)
        complete? (and (pos? len) (zero? (mod len 16)))
        head-len (if complete? (- len 16) (- len (mod len 16)))
        tail (.subarray msg head-len)
        last-block (if complete?
                     (bxor tail k1)
                     (bxor (bcat tail (u8 0x80) (js/Buffer.alloc (- 15 (.-length tail)))) k2))
        ;; CMAC is the last block of CBC-MAC over the message with that tweaked tail
        mac (aes "aes-128-cbc" key (js/Buffer.alloc 16) (bcat (.subarray msg 0 head-len) last-block))]
    (.subarray mac (- (.-length mac) 16))))

(defn- ccm-encrypt
  "Returns ciphertext || MIC."
  [key nonce plaintext mic-len]
  (let [c (crypto/createCipheriv "aes-128-ccm" key nonce #js {:authTagLength mic-len})
        ciphertext (bcat (.update c plaintext) (.final c))]
    (bcat ciphertext (.getAuthTag c))))

(defn- ccm-decrypt
  "Takes ciphertext || MIC, returns the plaintext or nil if the MIC is wrong."
  [key nonce data mic-len]
  (let [split (- (.-length data) mic-len)]
    (when (pos? split)
      (let [d (crypto/createDecipheriv "aes-128-ccm" key nonce #js {:authTagLength mic-len})]
        (.setAuthTag d (.subarray data split))
        (try
          (let [plaintext (.update d (.subarray data 0 split))]
            (.final d)
            plaintext)
          (catch :default _ nil))))))

;; --- key derivation (3.8.2) ------------------------------------------------

(defn s1 [m] (cmac (js/Buffer.alloc 16) m))

(defn k1 [n salt p] (cmac (cmac salt n) p))

(defn k2 [n p]
  (let [t (cmac (s1 (ascii "smk2")) n)
        t1 (cmac t (bcat p (u8 1)))
        t2 (cmac t (bcat t1 p (u8 2)))
        t3 (cmac t (bcat t2 p (u8 3)))]
    {:nid (bit-and (aget t1 15) 0x7f)
     :enc-key t2
     :privacy-key t3}))

(defn k3 [n]
  (let [t (cmac (s1 (ascii "smk3")) n)]
    (.subarray (cmac t (bcat (ascii "id64") (u8 1))) 8)))

(defn k4 [n]
  (let [t (cmac (s1 (ascii "smk4")) n)]
    (bit-and (aget (cmac t (bcat (ascii "id6") (u8 1))) 15) 0x3f)))

(defn net-key
  "Everything derived from a network key (master credentials)."
  [key]
  (assoc (k2 key (u8 0))
         :key key
         :network-id (k3 key)
         :beacon-key (k1 key (s1 (ascii "nkbk")) (bcat (ascii "id128") (u8 1)))))

(defn app-key [key]
  {:key key :aid (k4 key)})

;; --- network layer (3.4) ---------------------------------------------------

(defn- nonce [type octet seq src dst iv-index]
  (bcat (u8 type) (u8 octet) (u24 seq) (u16 src) (u16 dst) (u32 iv-index)))

(defn- network-nonce [proxy? ctl-ttl seq src iv-index]
  (if proxy?
    (nonce 3 0 seq src 0 iv-index)
    (nonce 0 ctl-ttl seq src 0 iv-index)))

(defn- privacy-mask
  "`enc` is EncDST || EncTransportPDU || NetMIC, its first 7 bytes are the
  privacy random that the header obfuscation is keyed on."
  [privacy-key iv-index enc]
  (.subarray (e privacy-key (bcat (js/Buffer.alloc 5) (u32 iv-index) (.subarray enc 0 7))) 0 6))

(defn encode-network
  "Encrypts and obfuscates a network PDU. `proxy?` selects the proxy nonce that
  proxy configuration messages are encrypted with."
  [{:keys [nid enc-key privacy-key]} {:keys [iv-index ctl ttl seq src dst proxy?]} transport-pdu]
  (let [ctl-ttl (bit-or (bit-shift-left ctl 7) ttl)
        enc (ccm-encrypt enc-key
                         (network-nonce proxy? ctl-ttl seq src iv-index)
                         (bcat (u16 dst) transport-pdu)
                         (if (= 1 ctl) 8 4))
        header (bcat (u8 ctl-ttl) (u24 seq) (u16 src))]
    (bcat (u8 (bit-or (bit-shift-left (bit-and iv-index 1) 7) nid))
          (bxor header (privacy-mask privacy-key iv-index enc))
          enc)))

(defn decode-network
  "Returns the decrypted network PDU as a map, or nil if it isn't for `net-key`."
  [{:keys [nid enc-key privacy-key]} iv-index pdu & {:keys [proxy?]}]
  (let [;; the IVI bit tells whether the sender is still on the previous IV index
        iv-index (if (= (bit-shift-right (aget pdu 0) 7) (bit-and iv-index 1))
                   iv-index
                   (dec iv-index))]
    (when (and (>= (.-length pdu) 14)
               (= nid (bit-and (aget pdu 0) 0x7f))
               (>= iv-index 0))
      (let [enc (.subarray pdu 7)
            header (bxor (.subarray pdu 1 7) (privacy-mask privacy-key iv-index enc))
            ctl-ttl (aget header 0)
            ctl (bit-shift-right ctl-ttl 7)
            seq (.readUIntBE header 1 3)
            src (.readUInt16BE header 4)]
        (when-let [plain (ccm-decrypt enc-key
                                      (network-nonce proxy? ctl-ttl seq src iv-index)
                                      enc
                                      (if (= 1 ctl) 8 4))]
          {:iv-index iv-index
           :ctl ctl
           :ttl (bit-and ctl-ttl 0x7f)
           :seq seq
           :src src
           :dst (.readUInt16BE plain 0)
           :transport-pdu (.subarray plain 2)})))))

;; --- transport layers (3.5, 3.6) -------------------------------------------

;; We only send unsegmented access messages (up to 11 bytes), which is all that
;; the SANlight commands need, but longer status messages arrive segmented.
;; Keys are maps: app keys carry their :aid, device keys don't.

(defn- access-nonce [{:keys [aid]} {:keys [iv-index seq src dst szmic]}]
  (nonce (if aid 1 2) (bit-shift-left (or szmic 0) 7) seq src dst iv-index))

(defn encode-access
  "Lower transport PDU carrying an unsegmented access message."
  [{:keys [key aid] :as k} meta payload]
  (when (> (.-length payload) 11)
    (throw (ex-info "access payload too long for an unsegmented message" {:length (.-length payload)})))
  (bcat (u8 (if aid (bit-or 0x40 aid) 0))
        (ccm-encrypt key (access-nonce k meta) payload 4)))

(defn encode-access-segments
  "Lower transport PDUs carrying a segmented access message. `meta` is that of
  the first segment, whose sequence number the encryption is based on."
  [{:keys [key aid] :as k} {:keys [seq] :as meta} payload]
  (let [upper (ccm-encrypt key (access-nonce k meta) payload 4)
        n (js/Math.ceil (/ (.-length upper) 12))]
    (when (> n 32)
      (throw (ex-info "access payload too long" {:length (.-length payload)})))
    (vec (for [i (range n)]
           (bcat (u8 (bit-or 0x80 (if aid (bit-or 0x40 aid) 0)))
                 ;; SZMIC 0 | SeqZero | SegO | SegN
                 (u24 (bit-or (bit-shift-left (bit-and seq 0x1fff) 10) (bit-shift-left i 5) (dec n)))
                 (.subarray upper (* 12 i) (min (.-length upper) (* 12 (inc i)))))))))

(defn parse-segment-ack
  "The fields of a segment acknowledgment, nil for other control PDUs."
  [pdu]
  (when (and (= 7 (.-length pdu)) (zero? (bit-and (aget pdu 0) 0x7f)))
    {:seq-zero (bit-and (bit-shift-right (.readUInt16BE pdu 1) 2) 0x1fff)
     :block-ack (.readUInt32BE pdu 3)}))

(defn parse-lower
  "Header fields of a lower transport access PDU. Unsegmented messages yield
  the upper transport PDU as :upper, segmented ones a :segment of it."
  [pdu]
  (let [b0 (aget pdu 0)]
    (if-not (bit-test b0 7)
      {:upper (.subarray pdu 1)}
      (let [h (.readUIntBE pdu 1 3)]
        {:szmic (bit-and (bit-shift-right h 23) 1)
         :seq-zero (bit-and (bit-shift-right h 10) 0x1fff)
         :seg-o (bit-and (bit-shift-right h 5) 0x1f)
         :seg-n (bit-and h 0x1f)
         :segment (.subarray pdu 4)}))))

(defn seq-auth
  "Sequence number of a segmented message's first segment, which its nonce is
  built from, recovered from the `seq` of any of its segments."
  [seq seq-zero]
  (let [candidate (bit-or (bit-and seq (bit-not 0x1fff)) seq-zero)]
    (if (> candidate seq) (- candidate 0x2000) candidate)))

(defn segment-ack
  "Lower transport control PDU acknowledging the segments `seg-os` of a
  message."
  [seq-zero seg-os]
  (bcat (u8 0)
        (u16 (bit-shift-left seq-zero 2))
        (u32 (reduce + (map #(js/Math.pow 2 %) seg-os)))))

(defn decrypt-access
  "Decrypts an upper transport access PDU; nil if `k` is not its key. For a
  reassembled message, `meta` needs its :szmic and the seq-auth as :seq."
  [{:keys [key] :as k} {:keys [szmic] :as meta} upper]
  (ccm-decrypt key (access-nonce k meta) upper (if (= 1 szmic) 8 4)))

;; --- access layer (3.7.3) --------------------------------------------------

(defn parse-access [payload]
  (let [b0 (aget payload 0)
        width (cond
                (not (bit-test b0 7)) 1
                (not (bit-test b0 6)) 2
                :else 3)]
    {:opcode (.readUIntBE payload 0 width)
     :params (.subarray payload width)}))

;; --- proxy protocol (6.3) --------------------------------------------------

(def proxy-types {:network 0 :beacon 1 :proxy-config 2 :provisioning 3})

(defn proxy-segments
  "Splits a PDU into proxy PDUs of at most `max-len` bytes each."
  [type pdu max-len]
  (let [type (proxy-types type)
        chunk (dec max-len)
        n (.-length pdu)]
    (if (<= n chunk)
      [(bcat (u8 type) pdu)]
      (let [parts (mapv #(.subarray pdu % (min n (+ % chunk))) (range 0 n chunk))
            sar (fn [i] (cond (zero? i) 0x40
                              (= i (dec (count parts))) 0xc0
                              :else 0x80))]
        (vec (map-indexed (fn [i part] (bcat (u8 (bit-or (sar i) type)) part)) parts))))))

(defn proxy-reassemble
  "Feeds one received proxy PDU into the reassembly state `acc`.
  Returns [acc' message], where message is {:type :pdu} once complete."
  [acc packet]
  (let [sar (bit-shift-right (aget packet 0) 6)
        type (get (zipmap (vals proxy-types) (keys proxy-types)) (bit-and (aget packet 0) 0x3f))
        data (.subarray packet 1)]
    (case sar
      0 [nil {:type type :pdu data}]
      1 [[data] nil]
      2 [(conj (or acc []) data) nil]
      3 [nil {:type type :pdu (apply bcat (conj (or acc []) data))}])))

;; --- secure network beacon (3.9.3) -----------------------------------------

(defn parse-beacon
  "Parses a secure network beacon; nil if it isn't one or isn't authentic."
  [{:keys [beacon-key]} pdu]
  (when (and (= 22 (.-length pdu))
             (= 1 (aget pdu 0))
             (.equals (.subarray (cmac beacon-key (.subarray pdu 1 14)) 0 8)
                      (.subarray pdu 14)))
    (let [flags (aget pdu 1)]
      {:key-refresh? (bit-test flags 0)
       :iv-update? (bit-test flags 1)
       :network-id (.subarray pdu 2 10)
       :iv-index (.readUInt32BE pdu 10)})))
