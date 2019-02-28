(ns shared.connection
  "Cope with the details of a single web socket connection"
  (:require [#?(:clj clojure.core.async
                :cljs cljs.core.async) :as async #?@(:clj [:refer [go]])]
            [clojure.spec.alpha :as s]
            [#?(:cljs frereth.apps.log-viewer.frontend.socket
                :clj frereth.apps.log-viewer.renderer.socket)
             :as web-socket]
            #?(:clj [frereth.cp.shared.util :as cp-util])
            [frereth.apps.shared.specs :as specs]
            [frereth.apps.shared.world :as world])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]])))

#?(:cljs (enable-console-print!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def :frereth/session-id :frereth/pid)

;; Think about the way that om-next maintains a stack of states to let
;; us roll back and forth.
;; It's tempting to use something like a datomic squuid for the ID
;; and put them into a list.
;; It's also tempting to not use this at all...if world states are updating
;; 60x a second, it seems like this will quicky blow out available RAM.
;; Not doing so would be premature optimization, but it seems like a
;; very obvious one.
(s/def ::state-id :frereth/pid)

;; Might be interesting to track deactivated sessions for historical
;; comparisons.
;; Q: Is there any point to setting up a pre log-in session?
;; A: Well, there's the obvious "anonymous" browsing.
;; But, given the main architecture, that seems iffy without a
;; websocket.
;; Anonymous browsing is definitely an important piece of the
;; puzzle: want to be able to connect to a blog and read it without
;; being tracked.
;; I need to think more about this, also.
;; There are degrees of "logged in" that get twisty when we're looking
;; at connections to multiple worlds/servers and potentially different
;; connection IDs.
;; I can be logged in to the local Server to view logs. I can also have
;; a Client connection to a remote Server to monitor its health.
;; And an anonymous Client connection to some other remote Server to
;; browse a blog.
;; [Q: Any point to building a blog engine like a regular web server
;; that people can read anonymously?]
;; And an authenticated Client connection to that same Server to write
;; new blog entries.
;; None of this matters for an initial proof of concept, but it's
;; important to keep in mind.
;; Because it "logged in" really happens after the
;; websocket connection, for most Worlds.
;; But that *is* going to depend on the World.
;; Note that the Session is a direct browser connection to this local
;; web server.
;; World connections beyond that will go through the Client interface
;; instead.
;; So this does make sense.
(s/def ::state #{::connected  ; ready to log in
                 ;; This next state really shouldn't be retained
                 ;; for very long.
                 ;; If the websocket drops and doesn't disconnect
                 ;; very quickly, the end-user should probably
                 ;; just need to log back in.
                 ;; This expectation needs to be tempered by the
                 ;; consideration that this is generally expected to
                 ;; happen as close to localhost as possible...right
                 ;; next to the acceptance of the reality that it isn't
                 ;; going to work out that way in practice.
                 ::disconnected  ; worlds have disconnected
                 ::disconnecting  ; websocket went away: need to disconnect worlds
                 ::pending  ; Awaiting web socket
                 ::active  ; web socket active
                 })

(s/def ::time-in-state inst?)

;; Q: What is this?
;; (another alias for :frereth/pid is tempting.
;; So is a java.security.auth.Subject)
#?(:clj (s/def ::subject any?))

(s/def :frereth/session-sans-history (s/keys :req [::session-id
                                                   ::state
                                                   ::state-id
                                                   ::specs/time-in-state
                                                   :frereth/worlds]
                                             :opt [#?(:clj ::subject)
                                                   ::web-socket/wrapper]))


(s/def ::history (s/map-of ::state-id :frereth/session-sans-history))

;; This name warns me that I have a botched-up abstraction
;; layer.
;; Or maybe just an incomplete refactoring.
;; This *does* represent an individual browser session.
;; But it's very confusing to call it that here in the
;; connection ns.
;; TODO: Rename to something like ::manager
;; ::connection is a little tempting, but also seems...well, it would
;; make destructuring cleaner, since there isn't any overlap.
(s/def :frereth/session (s/merge :frereth/session-sans-history
                                 (s/keys :req [::history])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef update-state
  :args (s/or :updating (s/cat :state :frereth/session
                               :new-state ::state
                               :update-fn (s/fspec :args (s/cat :current :frereth/session)
                                                   :ret :frereth/session))
              :simple (s/cat :state :frereth/session
                             :new-state ::state))
  :ret :frereth/session)
(defn update-state
  "This smells fishy. update-fn seems like something that should
  apply to an internal-state.

  The more I write in here, the more inclined I am to think that the
  connection is really just a meta-world."
  ([current new-state update-fn]
   (update-fn
    (update-state current new-state)))
  ([current new-state]
   (assoc (if-not new-state current
                  (assoc current ::state new-state))
          ::state-id #?(:clj (cp-util/random-uuid)
                        :cljs (random-uuid))
          ;; So we can time out the oldest connection if/when we get
          ;; overloaded.
          ;; Although that isn't a great algorithm. Should also
          ;; track session sources so we can prune back ones that
          ;; are being too aggressive and trying to open too many
          ;; world at the same time.
          ;; (Then again, that's probably exactly what I'll do when
          ;; I recreate a session, so there are nuances to
          ;; consider).
          ::specs/time-in-state #?(:clj (java.util.Date.)
                                   :cljs (js/Date.)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef activate
  :args (s/cat :state :frereth/session
               ;; FIXME: What is this?
               :web-socket ::web-socket)
  :fn (s/and #(= (-> % :args :state ::state)
                 ::pending)
             #(= (-> % :ret ::state)
                 ::active)
             #(= (-> % :args :state (dissoc ::state))
                 (-> % :ret (dissoc ::state
                                    ::web-socket))))
  :ret :frereth/session)
(defn activate
  "Web socket is ready to interact"
  [session web-socket]
  (update-state session ::active
                #(assoc % ::web-socket web-socket)))

(s/fdef activate-forked-world
  :args (s/cat :session :frereth/session
               :world-key :frereth/world-key
               :message-forwarder
               #?(:clj :frereth/renderer->client
                  :cljs :frereth/browser->worker))
  :ret :frereth/session)
(defn activate-forked-world
  "Transition World from forking to active"
  [session world-key message-forwarder]
  (update-state session nil
                #(update % :frereth/worlds
                         world/activate-forked
                         world-key
                         message-forwarder)))

(s/fdef add-pending-world
  :args (s/cat :current :frereth/session
               :world-key :frereth/world-key
               #?@(:cljs [:notification-channel ::world/notification-channel])
               :cookie ::world/cookie)
  :ret :frereth/session)
(defn add-pending-world
  [{:keys [::state-id
           ::specs/time-in-state
           :frereth/worlds]
    :as current}
   world-key
   #?(:cljs notification-channel)
   cookie]
  (update-state current
                nil
                (fn [current]
                  (assoc current
                         :frereth/worlds (world/add-pending worlds
                                                            world-key
                                                            #?(:cljs notification-channel)
                                                            #?(:clj cookie)
                                                            {})))))

(s/fdef create
  :args nil
  :ret :frereth/session)
(defn create
  ;; There's now an open question about when this should happen.
  ;; The obvious point seems to be when the browser loads the SPA.
  ;; But that wastes server resources and exposes us to
  ;; an easy DoS attack.
  ;; This is the point behind JWT and the Bearer authentication scheme.
  ;; OTOH:
  ;; This *is* useful for server-side introspection about what's going
  ;; on in "real-time" in the wild.
  ;; And it gets trickier when we get into the websocket interactions.
  "Create a new anonymous SESSION"
  []
  (update-state {} ::connected
                #(assoc % :frereth/worlds {})))

(s/fdef disconnect-all
  :args (s/cat :session ::session)
  :ret :frereth/session)
(defn disconnect-all
  [{:keys [:frereth/worlds]
    :as session}]
  (update-state session
                ::disconnecting
                (fn [{:keys [:frereth/worlds]
                      :as session}]
                  ;; FIXME: Need to cope with the disconnected signal from all workers.
                  (doseq [{{:keys [:frereth/disconnect!]
                            :as forwarder} #?(:clj :frereth/renderer->client
                                              :cljs :frereth/browser->worker)
                           :as world} worlds]
                    (if disconnect!
                      (disconnect!)
                      (println "Missing disconnect! among"
                               #?(:clj (cp-util/pretty world)
                                  :cljs world)
                               "\nin" (keys world)
                               "\nsearching inside"
                               #?(:clj :frereth/renderer->client
                                  :cljs :frereth/browser->worker))))
                  session)))

(s/fdef get-world
  :args (s/cat :session ::session
               :world-key :frereth/world-key)
  :ret (s/nilable ::world/world))
(defn get-world
  [session world-key]
  (world/get-world (:frereth/worlds session) world-key))

(s/fdef log-in
  :args (s/cat :state :frereth/state
               #?@(:clj [:subject ::subject]))
  :fn (s/and #(= (-> % :args :state ::state)
                 ::connected)
             #(= (-> % :ret ::state) ::pending)
             #(= (-> % :args :subject)
                 (-> % :ret ::subject)))
  :ret :frereth/session)
(defn log-in
  ;; Note that this is distinct from logging into a World.
  ;; That's really more of a frereth-server thing, probably
  ;; going through a client.
  ;; This is really about authenticating a direct browser
  ;; connection.
  "Change Session state.

  Handle the authentication elsewhere."
  [session-state #?(:clj subject)]
  (update-state session-state
                ::pending
                #?(:clj #(assoc % ::subject subject))))
