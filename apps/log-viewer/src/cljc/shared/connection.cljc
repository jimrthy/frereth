(ns shared.connection
  ;; Q: Is it worth just moving that to a shared .cljc ns instead?
  ;; ::subject probably doesn't make sense on the browser side,
  ;; but the rest of it (plus history) seems pretty relevant.
  "This seems very suspiciously like what I did originally with sessions.clj"
  ;; One major difference:
  ;; The original was designed to handle multiple Connections.
  ;; We definitely don't need that on the browser side.
  ;; So this approach makes sense.
  (:require [clojure.spec.alpha :as s]
            #?(:clj [frereth.cp.shared.util :as cp-util])))

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

(s/def ::time-in-state inst?)

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
  {::state-id #?(:cljs (random-uuid)
                 :clj (cp-utils/random-uuid))
   ::state ::connected
   ;; So we can time out the oldest connection if/when we get
   ;; overloaded.
   ;; Although that isn't a great algorithm. Should also
   ;; track session sources so we can prune back ones that
   ;; are being too aggressive and trying to open too many
   ;; world at the same time.
   ;; (Then again, that's probably exactly what I'll do when
   ;; I recreate a session, so there are nuances to
   ;; consider).
   ::time-in-state #?(:clj (java.util.Date.)
                      :cljs (js/Date.))
   :frereth/worlds {}})
