(ns sanvibe.cycle
  "Daylight cycles as EDN files, like

    {:name \"Mid Flower\"
     :points [[\"00:00\" 20]
              [\"05:00\" 80]
              ...
              [\"24:00\" 0]]}

  Points are [lamp clock time, percent]. The dimmer interpolates linearly
  between them and caps the result at the max brightness."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; The dimmer rejects cycles with fewer than 2 or more than 16 points, times
;; after 24:00 or values over 100 %, and cuts names after 29 bytes. It takes
;; unsorted points, a cycle without 00:00 or 24:00 and an empty name without
;; complaint though, and nobody knows what it makes of them: we refuse those.
(def ^:private max-points 16)
(def ^:private max-name-bytes 29)

(defn- pad2 [n] (.padStart (str n) 2 "0"))

(defn hh:mm [minute]
  (str (pad2 (quot minute 60)) ":" (pad2 (mod minute 60))))

(defn- parse-time [s]
  (when-let [[_ h m] (and (string? s) (re-matches #"(\d{2}):(\d{2})" s))]
    (let [h (js/parseInt h 10)
          m (js/parseInt m 10)]
      (when (and (< m 60) (<= (+ (* 60 h) m) 1440))
        (+ (* 60 h) m)))))

(defn- name-id
  "An ID for a cycle without one. The app gives each of its cycles an ID; we
  derive ours from the name, so that uploading the same file twice gives the
  same cycle. Kept below 2^30 like the IDs seen so far."
  [name]
  (bit-and (.readUInt32LE (.digest (.update (crypto/createHash "sha256") name)) 0) 0x3fffffff))

(defn- invalid [file message]
  (throw (ex-info (str file ": " message) {})))

(defn- parse-point [file [time percent :as point]]
  (let [minute (parse-time time)]
    (when-not (and (vector? point) (= 2 (count point)) minute
                   (integer? percent) (<= 0 percent 100))
      (invalid file (str "a point is [\"HH:MM\" percent] with 00:00..24:00 and 0..100, not " (pr-str point))))
    {:minute minute :percent percent}))

(defn- validate [file {:keys [id name points]}]
  (when-not (and (string? name) (seq name) (not (str/includes? name "\u0000")))
    (invalid file ":name must be a non-empty string"))
  (when (> (.-length (js/Buffer.from name "utf8")) max-name-bytes)
    (invalid file (str ":name can have at most " max-name-bytes " bytes (UTF-8), the dimmer cuts longer ones")))
  (when-not (or (nil? id) (and (integer? id) (<= 0 id 0xffffffff)))
    (invalid file ":id must be an unsigned 32 bit integer, or left out"))
  (when-not (and (vector? points) (<= 2 (count points) max-points))
    (invalid file (str ":points must be a vector of 2 to " max-points " points")))
  (let [points (mapv #(parse-point file %) points)
        minutes (map :minute points)]
    (when-not (and (= 0 (first minutes)) (= 1440 (last minutes)))
      (invalid file "the first point must be at 00:00 and the last one at 24:00"))
    (when-not (apply < minutes)
      (invalid file "the points' times must increase, for a sharp step use one minute apart like 12:00 and 12:01"))
    {:id (or id (name-id name))
     :name name
     :points points}))

(defn read-file
  "Reads and checks a daylight cycle EDN file, before we go near the dimmer."
  [file]
  (when-not (fs/existsSync file)
    (throw (ex-info (str "No such file: " file) {})))
  (let [data (try
               (edn/read-string (fs/readFileSync file "utf8"))
               (catch :default e
                 (invalid file (str "not valid EDN (" (ex-message e) ")"))))]
    (when-not (map? data)
      (invalid file "expected a map with :name and :points"))
    (validate file data)))

(defn ->edn
  "The cycle as an EDN file that read-file reads back, with `comment` on top."
  [{:keys [id name points]} comment]
  (str ";; " comment "\n"
       "{:name " (pr-str name) "\n"
       " :id " id "\n"
       " :points [" (str/join "\n          "
                              (for [{:keys [minute percent]} points]
                                (str "[\"" (hh:mm minute) "\" " percent "]")))
       "]}"))
