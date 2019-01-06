(ns renderer.sessions
  "Deal with the web-server view of session connections"
  ;; It's tempting to make this a shared .cljc.
  ;; But, really, the browser shouldn't know anything about this
  ;; abstraction layer.
  ;; Each Session here really represents a browser connection/websocket.
  (:require [clojure.spec.alpha :as s]
            [frereth.cp.shared.util :as cp-util]
            [integrant.core :as ig]
            [renderer.world :as world]
            [shared.specs :as specs]))

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

;; Might be interesting to track deactivated sessions for historical
;; comparisons.
(s/def ::session-state #{::active ::pending})

(s/def :frereth/session (s/keys :req [::session-state
                                      ::state-id
                                      ::specs/time-in-state]
                                :opt [::worlds]))
(s/def ::sessions (s/map-of :frereth/session-id :frereth/session))

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

(s/fdef get-active-session
  :args (s/cat :session-map ::sessions
               :session-id :frereth/session-id)
  :ret (s/nilable :frereth/session))
(defn get-active-session
  "Returns the active :frereth/session, if any"
  [session-map session-id]
  (when-let [session (get session-map session-id)]
    (when (= (::session-state session) ::active)
      session)))

(s/fdef get-world-in-active-session
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::world/world)
(defn get-world-in-active-session
  [sessions session-id world-key]
  (when-let [session (get-active-session sessions session-id)]
    (world/get-world (::worlds session) world-key)))

(s/fdef get-world-by-state-in-active-session
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :world-state ::world/connection-state)
  :ret (s/nilable ::world/world))
(defn get-world-by-state-in-active-session
  [sessions session-id world-key state]
  (when-let [world (get-world-in-active-session sessions
                                                session-id
                                                world-key)]
    (when (world/state-match? world state)
      world)))

(defmethod ig/init-key ::session-atom
  [_ _]
  (atom
   ;; For now, just hard-code some arbitrary random key as a baby-step.
   ;; This really needs to be injected here at login.
   ;; And it also needs to be a map that includes the time added so
   ;; we can time out the oldest if/when we get overloaded.
   ;; Although that isn't a great algorithm. Should also track
   ;; session sources so we can prune back ones that are being
   ;; too aggressive and trying to open too many world at the
   ;; same time.
   ;; (Then again, that's probably exactly what I'll do when I
   ;; recreate a session, so there are nuances to consider).
   ;; Here's the real reason the initial implementation was broken
   ;; into active/pending states at the root of the tree.
   ;; That allowed me to cheaply use the same key for all
   ;; sessions, so there was really only one (possibly both
   ;; pending and active).
   ;; Moving the session key to the root of the tree and tracking
   ;; the state this way means I'll have to add the initial
   ;; connection logic to negotiate this key so it's waiting and
   ;; ready when the websocket connects.
   {::state-id (cp-util/random-uuid)
    ;; It's very tempting to nest these a step further to make them
    ;; easy/obvious to isolate.
    test-key {::session-state ::pending
              ::time-in-state (java.time.Instant/now)}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

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
