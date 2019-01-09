(ns renderer.sessions-test
  (:require [renderer.sessions :as sessions]
            [clojure.test :as t :refer (deftest is)]))

(deftest lifecycle
  (let [initial (sessions/create)
        principal "test user"
        logged-in (sessions/log-in session principal)
        activated (sessions/activate )]
    (is (= (dissoc initial ::sessions/session-state)
           (dissoc logged-in ::sessions/session-state ::sessions/principal)))))
