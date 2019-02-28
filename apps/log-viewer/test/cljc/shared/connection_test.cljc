(ns shared.connection-test
  ;; When CIDER generated this originally, it named it
  ;; '.shared.connection-test
  ;; Which doesn't seem to make any sense at all.
  ;; Except that it worked.
  ;; TODO: Find out how.
  (:require [shared.connection :as connection]
            #?(:clj [clojure.test :as t :refer (deftest is testing)]
               :cljs [cljs.test :as t :include-macros true])
            [#?(:cljs frereth.apps.log-viewer.frontend.socket
                :clj frereth.apps.log-viewer.renderer.socket)
             :as web-socket]))

(deftest lifecycle
  ;; TODO: Fix this broken test.
  (let [connection (connection/create)
        #?@(:clj [principal "test user"])  ; Q: or subject?
        initial {}
        logged-in (connection/log-in initial #?(:clj principal))
        activated (connection/activate logged-in "web socket")]
    ;; This approach demonstrates the chicken/egg non-problem that I
    ;; keep thinking I'm running into:
    ;; You really do need to "log in" to a session directly from the
    ;; renderer to a "local" Server with which you'll be communicating
    ;; over a web socket.
    ;; After that, you can open up various Worlds. Many/all of them may
    ;; very well be remote, using the Client.
    (testing "Login"
      (is (= (dissoc initial ::connection/state)
             (dissoc logged-in ::connection/state ::connection/subject))))
    (testing "Connect websocket"
      (is (= (dissoc logged-in ::connection/state)
             (dissoc activated ::connection/state ::web-socket/wrapper))))))
