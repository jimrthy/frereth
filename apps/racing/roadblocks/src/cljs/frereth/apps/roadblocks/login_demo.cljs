(ns frereth.apps.roadblocks.login-demo
  "Provide an 'Attract' animation/demo

  And also a login mechanism so we can start the real thing

  That really conflats too very different ideas/states, but doesn't seem
  terrible as a starting point for proving the concept as a pre-auth
  demo"
  (:require
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.worker :as worker]
   [integrant.core :as ig]))

(s/fdef message-sender!
  :args (s/cat )
  :ret any?)
(defn message-sender!
  ;; This is really a mock-up of the web-socket connection.
  ;; Q: Is there any point to dumbing this down? Or should
  ;; I just go all-in and handle the messages as if this
  ;; were a real server?
  ;; At the very worst, I don't need to worry about messages
  ;; from multiple Clients
  "Pretend to send messages to the Server"
  [clock worker body]
  ;; I want a fake "Server" world-manager.
  ;; This should bypass all the networking hoops to send
  ;; messages to that.
  (worker/send-to-worker! clock worker :what-action? body))

(defmethod ig/init-key ::worker
  [_ {:keys [::lamport/clock]
      session-manager ::session/manager
      worker-manager ::worker/manager
      :as this}]
  (let [pk ::public-key  ; Q: Worth generating a "real" JWK?
        ch (async/chan)
        cookie ::cookie
        raw-key-pair ::key-pair]
    ;; Most of this life cycle is imposed by the need for real
    ;; worlds to generate crypto keys so the Client can be sure
    ;; to route message to the proper Server.
    ;; Doesn't particularly apply here.
    (session/add-pending-world! session-manager pk ch {})
    (let [demo-worker (worker/fork-world-worker worker-manager
                                                pk
                                                "/js/worker.js")
          sender! (partial message-sender! clock demo-worker)]
      (session/set-message-sender! session-manager pk sender!)
      (session/do-mark-forking session-manager pk cookie raw-key-pair)
      (.warn js/console "FIXME: How does demo-worker get merged back into `this`?")
      this)))
