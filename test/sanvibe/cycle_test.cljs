(ns sanvibe.cycle-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sanvibe.cycle :as cycle]))

(def ^:private dir (fs/mkdtempSync (path/join (os/tmpdir) "sanvibe-test-")))

(defn- file-with [content]
  (let [file (path/join dir (str (random-uuid) ".edn"))]
    (fs/writeFileSync file content)
    file))

(defn- error-reading [content]
  (try
    (cycle/read-file (file-with content))
    nil
    (catch :default e (ex-message e))))

(deftest example-cycle
  (let [{:keys [id name points]} (cycle/read-file "cycles/mid-flower.edn")]
    (is (= "Mid Flower" name))
    (is (= 1015331631 id))
    (is (= 11 (count points)))
    (is (= {:minute 721 :percent 0} (nth points 9)))))

(deftest edn-round-trip
  (let [c (cycle/read-file "cycles/mid-flower.edn")
        edn (cycle/->edn c "read back")]
    (is (str/starts-with? edn ";; read back\n"))
    (is (= c (cycle/read-file (file-with edn))))))

(deftest derived-id
  (let [content "{:name \"Veg\" :points [[\"00:00\" 50] [\"24:00\" 50]]}"
        id (:id (cycle/read-file (file-with content)))]
    (is (= id (:id (cycle/read-file (file-with content)))))
    (is (<= 0 id 0x3fffffff))))

(deftest invalid-cycles
  (testing "files"
    (is (str/includes? (error-reading "{:name") "not valid EDN"))
    (is (str/includes? (error-reading "[]") "expected a map")))
  (testing "names"
    (is (str/includes? (error-reading "{:points []}") ":name"))
    (is (str/includes? (error-reading (str "{:name \"" (apply str (repeat 30 "a")) "\"}")) "29 bytes")))
  (testing "points"
    (is (str/includes? (error-reading "{:name \"x\" :points [[\"00:00\" 0]]}") "2 to 16"))
    (is (str/includes? (error-reading "{:name \"x\" :points [[\"00:00\" 0] [\"24:01\" 0]]}") "00:00..24:00"))
    (is (str/includes? (error-reading "{:name \"x\" :points [[\"00:00\" 0] [\"24:00\" 101]]}") "0..100"))
    (is (str/includes? (error-reading "{:name \"x\" :points [[\"00:10\" 0] [\"24:00\" 0]]}") "first point"))
    (is (str/includes? (error-reading "{:name \"x\" :points [[\"00:00\" 0] [\"12:00\" 0] [\"12:00\" 9] [\"24:00\" 0]]}")
                       "must increase"))))

(deftest clock-times
  (is (= "00:00" (cycle/hh:mm 0)))
  (is (= "12:01" (cycle/hh:mm 721)))
  (is (= "24:00" (cycle/hh:mm 1440))))
