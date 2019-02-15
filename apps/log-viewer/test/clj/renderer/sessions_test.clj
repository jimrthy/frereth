(ns renderer.sessions-test
  (:require
   [clojure.test :as t :refer [are
                               deftest
                               is
                               testing]]
   [integrant.core :as ig]
   [renderer.sessions :as sessions]
   [shared.connection :as connection]))

(deftest lifecycle
  (let [system-definition {::sessions/session-atom nil}
        initial (ig/init)
        principal "test user"
        logged-in (connection/log-in initial principal)
        ;; Q: What did this do?
        activated (sessions/activate logged-in "web socket")]
    ;; This approach demonstrates the chicken/egg non-problem that I
    ;; keep thinking I'm running into:
    ;; You really do need to "log in" to a session directly from the
    ;; renderer to a "local" Server with which you'll be communicating
    ;; over a web socket.
    ;; After that, you can open up various Worlds. Many/all of them may
    ;; very well be remote, using the Client.
    (testing "Login"
      (is (= (dissoc initial ::sessions/state)
             (dissoc logged-in ::sessions/state ::sessions/principal))))
    (testing "Connect websocket"
      (is (= (dissoc logged-in ::sessions/state)
             (dissoc activated ::sessions/state ::sessions/web-socket))))))

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
