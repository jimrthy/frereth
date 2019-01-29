(ns renderer.sessions
  "Deal with the web-server view of session connections"
  ;; It's tempting to make this a shared .cljc.
  ;; But, really, the browser shouldn't know anything about this
  ;; abstraction layer.
  ;; Each Session here really represents a browser connection/websocket.
  (:require [clojure.pprint :refer (pprint)]
            [clojure.spec.alpha :as s]
            [frereth.apps.login-viewer.specs]
            [frereth.cp.shared.util :as cp-util]
            [integrant.core :as ig]
            [manifold.stream :as strm]
            [shared
             [specs :as specs]
             [world :as world]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: These need to be universally available
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

;; Q: What is this?
;; (another alias for :frereth/pid is tempting.
;; So is a java.security.auth.Subject)
(s/def ::subject any?)

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
;; Q: Any point to building a blog engine like a regular web server
;; that people can read anonymously?
;; And an authenticated Client connection to that some Server to write
;; new blog entries.
;; None of this matters for an initial proof of concept, but it's
;; important to keep in mind.
;; Because it probably means that "logged in" really happens after the
;; websocket connection.
;; But that's going to depend on the World.
;; Except that the Session is a direct browser connection to this local
;; web server.
;; World connections beyond that will go through the Client interface
;; instead.
;; So this does make sense.
(s/def ::state #{::connected  ; ready to log in
                 ::pending  ; Awaiting web socket
                 ::active  ; web socket active
                 })

(s/def ::web-socket (s/and strm/sink?
                           strm/source?))

(s/def :frereth/session (s/keys :req [::state
                                      ::state-id
                                      ::specs/time-in-state
                                      :frereth/worlds]
                                :opt [::subject
                                      ::web-socket]))
;; History has to fit in here somewhere.
;; It almost seems like it makes sense to have this recursively
;; inside :frereth/session. (Although circular references are
;; awful...maybe this should be limited to previous or "other"
;; states, with a link to previous).
;; It's tempting to split it up and keep each world's history
;; separate. It's also tempting to just automatically reject
;; that in knee-jerk response.
;; That probably isn't as terrible as it seems at first glance.
;; TODO: Sort out how this should work.
;; Look into  om-next's implementation
(s/def ::history (s/map-of ::state-id :frereth/session))
(s/def ::sessions (s/map-of :frereth/session-id :frereth/session))

;; The session- prefix is annoying in other namespaces.
;; Q: Does it make sense to convert to just ::atom?
(s/def ::session-atom (s/and #(instance? clojure.lang.Atom %)
                             #(s/valid? ::sessions (deref %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(def test-key
  "Placeholder for crypto key to identify a connection.

  Because I have to start somewhere, and that isn't with
  key exchange algorithms.

  Mostly, I don't want to copy/paste this any more than I
  must."
  [-39 -55 106 103
   -31 117 120 57
   -102 12 -102 -36
   32 77 -66 -74
   97 29 9 16
   12 -79 -102 -96
   89 87 -73 116
   66 43 39 -61])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;; Only needed because I'm fudging past the initial auth stage
(declare create log-in)
(defmethod ig/init-key ::session-atom
  [_ _]
  (atom
   ;; For now, just hard-code some arbitrary random key and
   ;; claims as a baby-step.
   ;; These really need to be injected here at login.
   ;; Except that it doesn't.
   ;; If someone opens a websocket with a valid unexpired claims token,
   ;; then that should be fine.
   ;; Moving the session key to the root of the tree and tracking
   ;; the state this way means I'll have to implement something like the
   ;; initial connection logic to negotiate this key so it's waiting
   ;; and ready when the websocket connects.
   (let [test-claims {}]
     {test-key (log-in (create) test-claims)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

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
  {::state-id (cp-util/random-uuid)
              ::state ::connected
              ;; So we can time out the oldest if/when we get
              ;; overloaded.
              ;; Although that isn't a great algorithm. Should also
              ;; track session sources so we can prune back ones that
              ;; are being too aggressive and trying to open too many
              ;; world at the same time.
              ;; (Then again, that's probably exactly what I'll do when
              ;; I recreate a session, so there are nuances to
              ;; consider).
   ::time-in-state (java.util.Date.)
   :frereth/worlds {}})

(s/fdef get-active-session
  :args (s/cat :session-map ::sessions
               :session-id :frereth/session-id)
  :ret (s/nilable :frereth/session))
(defn get-active-session
  "Returns the active :frereth/session, if any"
  [session-map session-id]
  (when-let [session (get session-map session-id)]
    (when (= (::state session) ::active)
      session)))

(s/fdef get-by-state
  :args (s/cat :sessions ::sessions
               :state ::state))
(defn get-by-state
  "Return sessions that match state"
  [sessions state]
  (reduce (fn [acc [k {session-state ::state
                       :as v}]]
            (if (= state session-state)
              (assoc acc k v)
              acc))
          {}
          sessions))

(s/fdef get-world-in-active-session
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::world/world)
(defn get-world-in-active-session
  [sessions session-id world-key]
  (when-let [session (get-active-session sessions session-id)]
    (world/get-world (:frereth/worlds session) world-key)))

(s/fdef get-world-by-state-in-active-session
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :world-state ::world/connection-state)
  :ret (s/nilable ::world/world))
(defn get-world-by-state-in-active-session
  [sessions session-id world-key state]
  ;; This has diverged from the implementation in world.
  ;; That has its own specific variations that start
  ;; with get-world-in-state.
  ;; FIXME: Consolidate these.
  (when-let [world (get-world-in-active-session sessions
                                                session-id
                                                world-key)]
    (when (world/state-match? world state)
      world)))

(s/fdef log-in
  :args (s/cat :state :frereth/state
               :subject ::subject)
  :fn (s/and #(= (-> % :args :state ::state) ::connected)
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
  [session-state subject]
  (assoc session-state
         ::state ::pending
         ::subject subject))

(s/fdef activate
  :args (s/cat :state :frereth/session
               :web-socket ::web-socket)
  :fn (s/and #(= (-> % :args :state ::state) ::pending)
             #(= (-> % :ret ::state) ::active)
             #(= (-> % :args :state (dissoc ::state))
                 (-> % :ret (dissoc ::state ::web-socket))))
  :ret :frereth/session)
(defn activate
  "Web socket is ready to interact"
  [session web-socket]
  (assoc session
         ::state ::active
         ::web-socket web-socket))

;; This is just for the sake of keeping things alphabetized
(declare get-pending-world)
(s/fdef activate-pending-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :client :frereth/renderer->client)
  :ret ::sessions)
(defn activate-pending-world
  ;; This feels more than a little ridiculous.
  "Transition World from pending to active"
  [sessions session-id world-key client]
  (if-let [session (get-active-session sessions session-id)]
    (update-in sessions
               [session-id
                :frereth/worlds]
            world/activate-pending world-key client)
    (do
      (println "No active session"
               session-id
               "\namong")
      (pprint sessions)
      sessions)))

(s/fdef add-pending-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :initial-state ::world/state)
  :ret ::sessions)
(defn add-pending-world
  [sessions session-id world-key cookie]
  (update sessions
          session-id
          (fn [{:keys [::state-id
                       ::specs/time-in-state
                       :frereth/worlds]
                :as current}]
            (let [worlds
                  (world/add-pending worlds world-key cookie {})]
              (assoc current
                     ::state-id (cp-util/random-uuid)
                     ::specs/time-in-state (java.util.Date.)
                     :frereth/worlds worlds)))))
(s/fdef deactivate
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id)
  :ret ::sessions)
(defn deactivate
  [sessions session-id]
  ;; It's very tempting to collect these.
  ;; Possibly in a ::deactivated bucket in sessions.
  ;; Possibly just by transitioning the ::state to
  ;; ::deactivated.
  ;; It's even a bit tempting to just store this in
  ;; a global.
  ;; For starters, just go with the simplest possible
  ;; approach

  ;; I definitely do want to do something along these lines
  #_(dissoc sessions session-id)
  ;; But for now, hack around the "real" life cycle
  ;; so I don't have to add anything like authentication.
  ;; Yet.
  ;; FIXME: Don't leave it this way.
  (update sessions session-id
          log-in {}))

(s/fdef deactivate-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::sessions)
(defn deactivate-world
  [session-map session-id world-key]
  (update-in session-map
             [session-id :frereth/worlds]
             world/deactivate world-key))

(s/fdef get-active-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret (s/nilable ::world/world))
(defn get-active-world
  [sessions session-id world-key]
  (get-world-by-state-in-active-session sessions
                                        session-id
                                        world-key
                                        ::world/active))

(s/fdef get-pending-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret (s/nilable ::world/world))
(defn get-pending-world
  [sessions session-id world-key]
  (get-world-by-state-in-active-session sessions
                                        session-id
                                        world-key
                                        ::world/pending))
