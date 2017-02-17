(ns goal-tender2.core-test
  (:require [clojure.test :refer (deftest is testing)]
            [datomic.api :as d]
            [goal-tender2.core :refer :all]))

(deftest a-test
  (testing "Basic schema installation succeeds"
    (let [db-name "something-random"
          cxn (do-installation db-name)]
      (try
        (is true "Yes, we got here")
        (finally
          (d/delete-database (build-url db-name)))))))
