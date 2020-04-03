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

(s/fdef pretend-to-forward-message-to-server!
  :args (s/cat :clock ::lamport/clock
               :worker ::worker/manager
               ;; FIXME: Surely I have a spec for this somewhere.
               :message-wrapper map?)
  :ret any?)
(defmulti pretend-to-forward-message-to-server!
  "This is really a fake Server world-manager"
  ;; This bypasses all the networking hoops to send
  ;; messages to that.
  ;; This is currently set up in a very similar manner to HTTP request
  ;; handlers.
  ;; The `body` is a map that should have :path-info, :request-method,
  ;; and :body keys.
  ;; I'm pretty sure I modeled this directly after Pedestal :request
  ;; maps.
  (fn [clock worker
       {:keys [:path-info
               :request-method]
        {:keys [:frereth/action
                :froreth/cookie
                :frereth/needs-dom-animation?
                :frereth/world-key]} :body
        :as wrapper}]
    (.info js/console
           "Message to send to server from"
           worker ":" (clj->js wrapper))
    ;; The real thing would increment the current clock tick
    ;; and add that as part of an envelope that gets forwarded along.
    ;; Well, maybe.
    ;; worker/on-worker-message did that as soon as the message
    ;; arrived there.
    ;; It might be worth doing again here, in case there's any weird
    ;; async things happening in the middle, but...well, maybe.
    ;; After all, this *is* pretending to be the server, which
    ;; would increment it at least once or twice
    (select-keys wrapper [:path-info :request-method])))

(defmethod pretend-to-forward-message-to-server! {:path-info "/api/v1/forked"
                                                  :request-method :put}
  ;; This is really a mock-up of the web-socket connection.
  ;; Q: Is there any point to dumbing this down? Or should
  ;; I just go all-in and handle the messages as if this
  ;; were a real server?
  ;; At the very worst, I don't need to worry about messages
  ;; from multiple Clients
  [clock worker wrapper]
  ;; Maybe we need to close the loop by responding with an ACK?
  ;; I desperately need to document this lifecycle.
  ;; FIXME: Start back by checking that
  ;; Although I should probably see whether there's a way to surmount
  ;; the error from three$build$three.js that's trying to access
  ;; document first.
  ;; TODO: That's next
  (.info js/console "Does the server need to do anything on :frereth/forked?")
  ;; Originally, I thought this needed to add the new Web Worker to the
  ;; session map here.
  ;; That thought was wrong.
  ;; shared.worker really takes care of that after it calls this
  ;; by calling session/do-mark-forked.
  )

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
    ;; For most worlds, this gets called inside
    ;; shared.worker/do-build-actual-worker
    (session/do-mark-forking session-manager pk cookie raw-key-pair)
    (let [demo-worker (worker/fork-world-worker worker-manager
                                                pk
                                                "/js/worker.js")
          sender! (partial pretend-to-forward-message-to-server! clock demo-worker)]
      (session/set-message-sender! session-manager pk sender!)
      this)))
