(ns renderer.sessions-test
  (:require
   [clojure.test :as t :refer [are
                               deftest
                               is
                               testing]]
   [integrant.core :as ig]
   [renderer.sessions :as sessions]
   [shared.connection :as connection]))

(deftest state-retrieval
  (let [sessions {1 {::sessions/state ::a}
                  2 {::sessions/state ::a}
                  3 {::sessions/state ::a}
                  4 {::sessions/state ::b}
                  5 {::sessions/state ::b}
                  6 {::sessions/state ::b}
                  7 {::sessions/state ::c}
                  8 {::sessions/state ::c}
                  9 {::sessions/state ::c}}]
    (are [state expected-ks]
        (let [filtered (sessions/get-by-state sessions state)]
          (and (= 3 (count filtered))
               (= expected-ks (set (keys filtered)))))
      ::a #{1 2 3}
      ::b #{4 5 6}
      ::c #{7 8 9})))
