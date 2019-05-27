(ns renderer.sessions
  "Deal with the web-server view of session connections"
  ;; It's tempting to make this a shared .cljc.
  ;; But, really, the browser shouldn't know anything about this
  ;; abstraction layer.
  ;; Each Session here really represents a browser connection/websocket.
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.shared.connection :as connection]
            [frereth.apps.shared.specs]  ; again w/ the shared.specs overlap
            [frereth.apps.shared.world :as world]
            [frereth.cp.shared.util :as cp-util]
            [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

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
     {test-key (connection/log-in (connection/create) test-claims)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef get-active-session
  :args (s/cat :session-map ::sessions
               :session-id :frereth/session-id)
  :ret (s/nilable :frereth/session))
(defn get-active-session
  "Returns the active :frereth/session, if any"
  [session-map session-id]
  (when-let [session (get session-map session-id)]
    (when (= (::connection/state session) ::connection/active)
      session)))

(s/fdef get-by-state
  :args (s/cat :sessions ::sessions
               :state ::connection/state))
(defn get-by-state
  "Return sessions that match state"
  [sessions state]
  (reduce (fn [acc [k {session-state ::connection/state
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
    (connection/get-world session world-key)))

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

(s/fdef activate-forking-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :client :frereth/renderer->client)
  :ret ::sessions)
(defn activate-forking-world
  "Transition World from pending to active"
  [sessions session-id world-key client]
  (if (get-active-session sessions session-id)
    (update sessions
            session-id
            connection/activate-forked-world world-key client)
    (do
      ;; FIXME: Convert to weald
      (println "No active session"
               session-id
               "\namong"
               (cp-util/pretty sessions))
      sessions)))

(s/fdef add-pending-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :initial-state ::world/state)
  :ret ::sessions)
(defn add-pending-world
  [sessions session-id world-key initial-state]
  (update sessions
          session-id
          connection/add-pending-world world-key initial-state))

(s/fdef disconnect
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id)
  :ret ::sessions)
(defn disconnect
  "Web socket connection dropped"
  [sessions session-id]
  (update sessions session-id
          (fn [session]
            (if session
              (let [session (connection/disconnect-all session)]
                ;; For now, hack around the "real" life cycle
                ;; so I don't have to add anything like authentication.
                ;; Yet.
                ;; FIXME: Don't leave it this way.
                (connection/log-in session {}))
              (do
                (println "Trying to disconnect missing session"
                         session-id
                         "among"
                         (cp-util/pretty sessions)))))))

(s/fdef deactivate-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::sessions)
(defn deactivate-world
  [session-map session-id world-key]
  (update-in session-map
             [session-id :frereth/worlds]
             world/mark-disconnecting world-key))

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
