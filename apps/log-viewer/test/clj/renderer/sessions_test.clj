(ns renderer.sessions-test
  (:require [renderer.sessions :as sessions]
            [clojure.test :as t :refer (deftest is testing)]))

(deftest lifecycle
  (let [initial (sessions/create)
        principal "test user"
        logged-in (sessions/log-in initial principal)
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
