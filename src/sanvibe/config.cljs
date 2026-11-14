(ns sanvibe.config
  "The mesh network as exported by the SANlight app, and what we keep between
  runs."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [nbb.core :refer [*file*]]
            [sanvibe.mesh :as mesh]))

(def ^:private root (path/resolve (path/dirname *file*) "../.."))

;; The export of the SANlight app (Settings > Mesh Maintenance > Export Mesh).
;; It holds the keys of the mesh network, without them the dimmer ignores us.
(def mesh-file (or (.. js/process -env -SANLIGHT_MESH) (path/join root "SANlightMesh.json")))
(def ^:private state-file (path/join root ".sanlight-state.json"))

;; The dimmer's vendor model: company 0x0A8B, model 0x0001.
(def ^:private dimmer-model "0A8B0001")

(defn- read-json [file]
  (js->clj (js/JSON.parse (fs/readFileSync file "utf8")) :keywordize-keys true))

(defn- hex->int [s] (js/parseInt s 16))

(defn load-network []
  (when-not (fs/existsSync mesh-file)
    (throw (ex-info (str "No mesh export at " mesh-file) {})))
  (let [{:keys [netKeys appKeys nodes provisioners]} (read-json mesh-file)
        app-keys (into {} (map (juxt :index #(mesh/app-key (mesh/hex->buf (:key %))))) appKeys)
        dimmers (vec (for [{:keys [name unicastAddress elements]} nodes
                           {:keys [index models]} elements
                           {:keys [modelId bind]} models
                           :when (= dimmer-model (str/upper-case modelId))]
                       {:name name
                        :address (+ (hex->int unicastAddress) index)
                        :app-key (app-keys (first bind))}))]
    (when (empty? dimmers)
      (throw (ex-info (str "No SANlight dimmer in " mesh-file) {})))
    {:net-key (mesh/net-key (mesh/hex->buf (:key (first netKeys))))
     :dimmers dimmers
     :allocated (vec (for [{:keys [allocatedUnicastRange]} provisioners
                           {:keys [lowAddress highAddress]} allocatedUnicastRange]
                       [(hex->int lowAddress) (hex->int highAddress)]))}))

(defn load-state
  "What has to survive between runs. :src and :seq are our identity on the
  mesh: nodes drop messages whose sequence number isn't above the last one
  they saw from that address (replay protection). The address is one that the
  app hasn't reserved for itself or other phones. :peripheral is the dimmer we
  connected through last time."
  [{:keys [allocated]}]
  (let [free? (fn [address] (not-any? (fn [[low high]] (<= low address high)) allocated))]
    (atom (if (fs/existsSync state-file)
            (read-json state-file)
            {:src (first (filter free? (shuffle (range 0x6000 0x8000))))
             :seq 0}))))

(defn save-state! [state]
  (fs/writeFileSync state-file (js/JSON.stringify (clj->js @state))))

(defn next-seq! [state]
  (let [{:keys [seq]} (swap! state update :seq inc)]
    (save-state! state)
    seq))
