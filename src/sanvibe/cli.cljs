(ns sanvibe.cli
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [promesa.core :as p]
            [sanvibe.config :as config]
            [sanvibe.cycle :as daylight]
            [sanvibe.dimmer :as dimmer]
            [sanvibe.log :as log]
            [sanvibe.proxy :as proxy]))

(def ^:private usage
  "Usage: sanvibe [COMMAND]

  info                What the dimmers tell: max brightness, output, lamp
                      clock, signal, daylight cycle. The default.
  brightness          The max brightness and the output.
  brightness PERCENT  Sets the max brightness, which caps the daylight cycle:
                      0 turns the light off, otherwise 20..100.
  cycle               Prints the daylight cycle as EDN, to keep or edit it:
                        sanvibe cycle > my-cycle.edn
  cycle FILE          Uploads a daylight cycle from such a file. It replaces
                      the dimmer's one, the only one it keeps.
  help                This text, also -h or --help.

Every command works on all SANlight dimmers in the mesh. It needs the mesh
export of the SANlight app (Settings > Mesh Maintenance > Export Mesh) as
SANlightMesh.json in the project directory, or at $SANLIGHT_MESH.
The app must be closed: a dimmer only talks to one device at a time.")

(defn- label [{:keys [name address]}]
  (str name " (" (log/address address) ")"))

(defn- show [conn {:keys [name] :as d}]
  (p/let [_ (log/step (str (label d) ": reading the brightness..."))
          max (dimmer/max-brightness conn d)
          output (dimmer/output conn d)]
    (println (str name ": " max "% (output now " output "%)"))))

(defn- set-and-verify [conn {:keys [name] :as d} percent]
  (p/let [_ (log/step (str (label d) ": setting the brightness to " percent "%..."))
          acknowledged (dimmer/set-max-brightness! conn d percent)
          _ (println (str name ": set to " percent "% (acknowledged, output " acknowledged "%)"))
          _ (log/step (str (label d) ": reading it back..."))
          max (dimmer/max-brightness conn d)
          output (dimmer/output conn d)]
    (if (= percent max)
      (println (str name ": verified, the dimmer reports " max "% (output now " output "%)"))
      (throw (ex-info (str name ": asked for " percent "%, but the dimmer reports " max "%") {})))))

(defn- pad2 [n] (.padStart (str n) 2 "0"))

(defn- clock
  "HH:MM:SS of a time of day in ms."
  [ms]
  (let [s (quot (mod ms 86400000) 1000)]
    (str (pad2 (quot s 3600)) ":" (pad2 (mod (quot s 60) 60)) ":" (pad2 (mod s 60)))))

(defn- bar [percent]
  (let [full (js/Math.round (/ percent 10))]
    (str (str/join (repeat full "█")) (str/join (repeat (- 10 full) "░")))))

(defn- signal [dbm]
  (str dbm " dBm (" (cond (>= dbm -65) "good" (>= dbm -80) "fair" :else "weak") ")"))

(defn- cycle-lines
  "The daylight cycle's points, with a marker where the lamp clock is now."
  [points clock-ms]
  (let [now (quot (mod clock-ms 86400000) 60000)
        line (fn [{:keys [minute percent]}]
               (str "    " (daylight/hh:mm minute) "  " (bar percent) " " percent "%"))
        [before after] (split-with #(<= (:minute %) now) points)]
    (concat (map line before)
            [(str "  ▸ " (daylight/hh:mm now) "  now")]
            (map line after))))

(defn- info [conn {:keys [name] :as d}]
  (p/let [_ (log/step (str (label d) ": reading its status..."))
          {:keys [clock-ms output max-brightness cycle]} (dimmer/status conn d)
          rssi (proxy/rssi conn)
          now (js/Date.)
          local-ms (* 1000 (+ (* 3600 (.getHours now)) (* 60 (.getMinutes now)) (.getSeconds now)))]
    (println (str/join "\n"
                       (concat
                         [""
                          (str name " (" (log/address (:address d)) ")")
                          (str "  Max brightness  " max-brightness "%")
                          (str "  Output now      " output "%")
                          (str "  Lamp clock      " (clock clock-ms) "   (Mac: " (clock local-ms) ")")
                          (str "  Signal          " (signal rssi))
                          (str "  Daylight cycle  " (:name cycle) " (by lamp clock time)")]
                         (cycle-lines (:points cycle) clock-ms)
                         [""])))))

(defn- print-cycle [conn {:keys [name] :as d}]
  (p/let [_ (log/step (str (label d) ": reading its daylight cycle..."))
          cycle (dimmer/daylight-cycle conn d)]
    (println (daylight/->edn cycle (str "Daylight cycle of " name ". Upload with: sanvibe cycle FILE")))))

(defn- upload-cycle [conn {:keys [name] :as d} cycle]
  (p/let [_ (log/step (str (label d) ": uploading the daylight cycle " (:name cycle) "..."))
          output (dimmer/set-daylight-cycle! conn d cycle)
          _ (println (str name ": uploaded " (:name cycle) " (acknowledged, output now " output "%)"))
          _ (log/step (str (label d) ": reading it back..."))
          stored (dimmer/daylight-cycle conn d)]
    (if (= cycle stored)
      (println (str name ": verified, the dimmer runs " (:name stored) " with " (count (:points stored)) " points"))
      (throw (ex-info (str name ": the dimmer runs a different daylight cycle than the uploaded one:\n"
                           (daylight/->edn stored "read back"))
                      {})))))

(defn- control
  "Runs `f` with the connection for every dimmer in the mesh."
  [f]
  (p/let [{:keys [dimmers] :as network} (config/load-network)
          _ (log/step (str "Mesh export " config/mesh-file " with " (str/join ", " (map label dimmers))))
          conn (proxy/connect network)
          _ (reduce (fn [previous d]
                      (p/let [_ previous]
                        (f conn d)))
                    (p/resolved nil)
                    dimmers)]
    (proxy/disconnect conn)))

(defn- fail [message]
  (js/console.error message)
  (js/process.exit 1))

(defn- run
  "Runs `f` for every dimmer, then exits."
  [f]
  (-> (control f)
      (p/then (fn [_] (js/process.exit 0)))
      (p/catch (fn [e] (fail (ex-message e))))))

(defn- brightness-command [{:keys [percent]}]
  (run (if percent #(set-and-verify %1 %2 percent) show)))

(defn- cycle-command [{:keys [file]}]
  (if file
    ;; read the file before connecting, which takes a while
    (let [cycle (daylight/read-file file)]
      (run #(upload-cycle %1 %2 cycle)))
    (run print-cycle)))

(defn- command
  "An entry of the babashka.cli dispatch table. `f` gets the parsed options."
  [cmds f & {:as more}]
  (merge {:cmds cmds
          :fn (fn [{:keys [opts args dispatch]}]
                (cond
                  (:help opts) (println usage)
                  (seq args) (fail (str (if (seq dispatch) "Too many arguments: " "Unknown command: ")
                                        (str/join " " args) "\n\n" usage))
                  :else (f opts)))}
         more))

(def ^:private commands
  [(command ["info"] (fn [_] (run info)))
   (command ["brightness"] brightness-command :args->opts [:percent])
   (command ["cycle"] cycle-command :args->opts [:file])
   (command ["help"] (fn [_] (println usage)))
   (command [] (fn [_] (run info)))])

(def ^:private parse-options
  {:spec {:percent {:coerce :long
                    :validate #(or (zero? %) (<= 20 % 100))}
          :help {:alias :h :coerce :boolean}}
   :restrict [:percent :file :help]
   :error-fn (fn [{:keys [option msg]}]
               (fail (if (= :percent option)
                       "Brightness must be 0 (off) or between 20 and 100."
                       (str msg "\n\n" usage))))})

(defn -main [& args]
  (try
    (cli/dispatch commands args parse-options)
    (catch :default e (fail (ex-message e)))))

;; what loading this file returns to bin/sanvibe.mjs
#js {:main -main}
