(ns sanvibe.dimmer
  "Messages of the SANlight dimmer's vendor model."
  (:require [promesa.core :as p]
            [sanvibe.mesh :as mesh]
            [sanvibe.proxy :as proxy]))

;; Opcodes
;; the status opcode is the request's + 1. Careful when adding more: some 
;; messages that look like getters are setters, and without parameters they set 
;; 0 (0x06 turns the light off, 0x0A resets the lamp clock of the daylight 
;;cycle).
(def ^:private set-daylight-cycle 0x01) ; daylight cycle -> lamp clock, output; ff ff ff ff if rejected
(def ^:private get-daylight-cycle 0x03) ; -> daylight cycle
(def ^:private set-max-brightness 0x06) ; u8 percent -> lamp clock, output
(def ^:private get-max-brightness 0x08) ; -> u8 percent
(def ^:private get-output 0x0c)         ; -> u32 lamp clock (ms), u16 output (0.1%)
(def ^:private get-status 0x0e)         ; -> lamp clock, output, max brightness, daylight cycle

(defn- vendor-opcode [opcode]
  (bit-or (bit-shift-left (bit-or 0xc0 opcode) 16) 0x8b0a))

(defn- vendor-request [conn {:keys [address app-key]} opcode params]
  (proxy/request conn app-key address
                 (mesh/bcat (mesh/u24 (vendor-opcode opcode)) params)
                 (vendor-opcode (inc opcode))))

(defn- output-percent
  "The output brightness in a lamp clock + output status."
  [params]
  (/ (.readUInt16LE params 4) 10))

(defn set-max-brightness!
  "Sets the brightness that caps the daylight cycle, the light's output follows
  right away. Returns the output that the dimmer acknowledges with."
  [conn dimmer percent]
  (p/let [params (vendor-request conn dimmer set-max-brightness (mesh/u8 percent))]
    (output-percent params)))

(defn max-brightness [conn dimmer]
  (p/let [params (vendor-request conn dimmer get-max-brightness (js/Buffer.alloc 0))]
    (aget params 0)))

(defn output
  "The current output brightness in percent: the daylight cycle's value at
  the lamp clock's time, capped by the max brightness."
  [conn dimmer]
  (p/let [params (vendor-request conn dimmer get-output (js/Buffer.alloc 0))]
    (output-percent params)))

(defn- parse-cycle
  "A daylight cycle: u32 id, u8 count, count x (u16 minute of the day, u8
  percent), NUL terminated name."
  [params offset]
  (let [n (aget params (+ offset 4))
        points-start (+ offset 5)
        name-start (+ points-start (* 3 n))]
    {:id (.readUInt32LE params offset)
     :points (vec (for [i (range n)
                        :let [at (+ points-start (* 3 i))]]
                    {:minute (.readUInt16LE params at)
                     :percent (aget params (+ at 2))}))
     :name (.toString params "utf8" name-start (.indexOf params 0 name-start))}))

(defn- encode-cycle
  "The daylight cycle in the layout that parse-cycle reads."
  [{:keys [id name points]}]
  (let [head (js/Buffer.alloc (+ 5 (* 3 (count points))))]
    (.writeUInt32LE head id 0)
    (.writeUInt8 head (count points) 4)
    (doseq [[i {:keys [minute percent]}] (map-indexed vector points)]
      (.writeUInt16LE head minute (+ 5 (* 3 i)))
      (.writeUInt8 head percent (+ 7 (* 3 i))))
    (mesh/bcat head (js/Buffer.from name "utf8") (mesh/u8 0))))

(defn daylight-cycle
  "The daylight cycle the dimmer runs, the only one it keeps."
  [conn dimmer]
  (p/let [params (vendor-request conn dimmer get-daylight-cycle (js/Buffer.alloc 0))]
    (parse-cycle params 0)))

(defn set-daylight-cycle!
  "Replaces the dimmer's daylight cycle, the output follows right away.
  Returns the output that the dimmer acknowledges with."
  [conn dimmer cycle]
  (p/let [params (vendor-request conn dimmer set-daylight-cycle (encode-cycle cycle))]
    (when-not (= 6 (.-length params))
      ;; it takes 2 to 16 points, times up to 24:00 and values up to 100 %
      (throw (ex-info (str "The dimmer rejected the daylight cycle (answer " (mesh/buf->hex params) ").") {})))
    (output-percent params)))

(defn status
  "Everything the dimmer tells about its state in one go: the lamp clock (ms
  of the day) that the daylight cycle runs on, the output and max brightness,
  and the daylight cycle itself."
  [conn dimmer]
  (p/let [params (vendor-request conn dimmer get-status (js/Buffer.alloc 0))]
    {:clock-ms (.readUInt32LE params 0)
     :output (output-percent params)
     :max-brightness (aget params 6)
     :cycle (parse-cycle params 7)}))
