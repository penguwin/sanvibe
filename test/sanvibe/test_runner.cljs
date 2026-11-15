(ns sanvibe.test-runner
  "Runs the tests: npm test"
  (:require [cljs.test :as t]
            [sanvibe.cycle-test]
            [sanvibe.mesh-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(defn -main []
  (t/run-tests 'sanvibe.cycle-test
               'sanvibe.mesh-test))
