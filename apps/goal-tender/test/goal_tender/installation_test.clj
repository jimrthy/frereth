(ns goal-tender.installation-test
  (:require [clojure.test :refer (deftest is)]
            [goal-tender.installation :refer :all]
            [com.jimrthy.substratum.installer :as sub-inst]))

(let [uri-description {}]
  (sub-inst/install-platform uri-description)
  (defn wrap-test
    [f]
    (let [fake-connection (something uri-description)]
      (do uri-description)
      (f fake-connection))))

(deftest verify-installation
  (wrap-test (fn [_]
               (is true))))
