(ns sanvibe.log
  "Progress output. It goes to stderr, so that stdout only carries results.")

(def ^:private start (js/Date.now))
(def ^:private tty? (.-isTTY js/process.stderr))

(defn step [message]
  (let [elapsed (.toFixed (/ (- (js/Date.now) start) 1000) 1)
        line (str "[" (.padStart elapsed 5) "s] " message)]
    ;; dimmed, so that the results stand out
    (.write js/process.stderr (str (if tty? (str "\u001b[2m" line "\u001b[0m") line) "\n"))))

(defn address [n]
  (str "0x" (.toUpperCase (.padStart (.toString n 16) 4 "0"))))
