(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require
   [backend.event-bus :as bus]
   [backend.specs :as backend-specs]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.world :as world]
   [frereth.apps.shared.connection :as connection]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.specs]
   [frereth.apps.shared.serialization :as serial]
   [frereth.cp.shared.crypto :as crypto]
   [frereth.cp.shared.util :as cp-util]
   [frereth.weald.logging :as log]
   [frereth.weald.specs :as weald]
   [io.pedestal.http.route :as route]
   [manifold.bus :as event-bus]
   [manifold.deferred :as dfrd]
   [manifold.stream :as strm]
   [renderer.handlers :as handlers]
   [renderer.sessions :as sessions])
  (:import clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; This is a serializable value that will get converted to travel
;; across a wire.
(s/def :frereth/body any?)

(s/def ::pre-session (s/keys :req [::lamport/clock
                                   ::bus/event-bus
                                   ::weald/logger
                                   ::weald/state-atom
                                   ::sessions/session-atom
                                   ::connection/web-socket]))

(s/def ::context map?)
;; Q: Right?
(s/def ::terminator (s/fspec :args (s/cat :context ::context)
                             :ret boolean?))
(s/def ::terminators (s/coll-of ::terminator))
(s/def ::session (s/merge ::pre-session
                          (s/keys :req [::terminators])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; Q: Put these in another System Component that rotates them once a
;; minute?
;; Better Q: Is this approach really a good alternative?
(def minute-key
  "Symmetric key used for encrypting short-term key pairs in a Cookie

  (using the CurveCP, not web, sense of the term)"
  (atom (crypto/random-key)))
(def previous-minute-key
  "Old symmetric key used for encrypting short-term key pairs in Cookie

  (using the CurveCP, not web, sense of the term)"
  (atom (crypto/random-key)))

(comment (vec @minute-key)
         (vec @previous-minute-key)
         test-key)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef finalize-login!
  :args (s/cat :session ::session
               :wrapper string?)
  :ret any?)
(defn finalize-login!
  "Client might have authenticated over websocket"
  [{lamport ::lamport/clock
    :keys [::bus/event-bus
           ::weald/logger
           ::sessions/session-atom
           ::connection/web-socket]
    log-state-atom ::weald/state-atom
    :as session}
   message-wrapper]
  (swap! log-state-atom
         #(log/info %
                    ::finalize-login!
                    "Initial websocket message"
                    {::message message-wrapper}))
  (try
    (if (and (not= ::drained message-wrapper)
             (not= ::timed-out message-wrapper))
      (let [envelope (serial/deserialize message-wrapper)
            {{:keys [:frereth/session-id]
              :as params} :params
             :as request} (:request envelope)]
        (swap! log-state-atom
               #(log/debug %
                           ::finalize-login!
                           "Key pulled"
                           {::deserialized envelope
                            ::params params}))
        (try
          (let [session-map (sessions/get-by-state @session-atom ::connection/pending)]
            (swap! log-state-atom
                   #(log/debug %
                               ::finalize-login!
                               "Trying to activate session"
                               {:frereth/session-id session-id
                                ::session-id-type (type session-id)
                                ::sessions/session session-map})))
          (catch Exception ex
            ;; This was a nasty error that was very difficult to debug.
            ;; Q: Is it worth trying to log this if the message about
            ;; logging the session-id/session-state failed?
            ;; Well, the get-by-state could have also failed.
            (swap! log-state-atom #(log/exception %
                                                  ex
                                                  ::finalize-login!
                                                  "Failed trying to log activation details"
                                                  {:frereth/session-id session-id
                                                   ::sessions/session-atom session-atom}))))
        (if (get @session-atom session-id)
          (try
            (swap! log-state-atom #(log/debug %
                                              ::finalize-login!
                                              "Activating session"
                                              {:frereth/session-id session-id}))
            ;; FIXME: Don't particularly want the session-atom in here.
            ;; Q: Is there any way to avoid that?
            (swap! session-atom
                   (fn [sessions]
                     (update sessions session-id
                             connection/activate
                             web-socket)))
            (swap! log-state-atom #(log/debug %
                                              ::finalize-login!
                                              "Swapped:"
                                              {::connection/web-socket web-socket
                                               ::sessions/sessions @session-atom}))
            ;; Set up the message handler
            ;; Q: Does it make sense to move both routes and raw-
            ;; interceptors up the creation chain?
            (let [routes (handlers/build-routes)
                  raw-interceptors [handlers/outer-logger
                                    handlers/session-extractor
                                    handlers/request-deserializer
                                    (handlers/build-ticker lamport)
                                    ;; This brings up an interesting point.
                                    ;; Right now, I'm strictly basing dispatch
                                    ;; on the :action, which is basically the
                                    ;; HTTP verb.
                                    ;; At least, that's the way I've been
                                    ;; thinking about it.
                                    ;; Q: Would it make sense to get more
                                    ;; extensive about the options here?
                                    ;; Right now, the "verbs" amount to
                                    ;; :frereth/forked
                                    ;; :frereth/forking
                                    ;; And an error reporter for :default
                                    ;; The fact that there are so few options
                                    ;; for this makes me question this design
                                    ;; decision to feed those messages through
                                    ;; an interceptor chain.
                                    ;; This *is* a deliberately stupid app with
                                    ;; no real reason to provide feedback to the
                                    ;; server.
                                    ;; So...maybe there will be more options
                                    ;; along these lines in some future
                                    ;; iteration.
                                    ;; However...dragging this out for the sake
                                    ;; of hypothetical future benefit seems like
                                    ;; a really bad idea.
                                    route/query-params
                                    route/path-params-decoder
                                    handlers/inner-logger]
                  ;; For now, go with 1 event-bus per session.
                  ;; More realistically, want 1 event-bus associated with each world's
                  ;; renderer connection per session.
                  ;; Minimize the risk of bleeding messages among different worlds
                  ;; associated with the same session.
                  ;; Really, this means another hop in the event-bus chain:
                  ;; web-socket -> session -> world.
                  event-bus (event-bus/event-bus strm/stream)
                  ;; This is another opportunity to
                  ;; learn from om-next.
                  ;; Possibly.
                  ;; TODO: Review how it's replaced
                  ;; Om's cursors.
                  ;; That's really more relevant
                  ;; for the world-state.
                  ;; Don't want to
                  ;; forward the session-atom,
                  ;; but don't have a choice.
                  ;; If we constructed the partial
                  ;; with the current session,
                  ;; handlers wouldn't be able to
                  ;; pick up state changes.
                  ;; There's a flip side to this: it seems like it would be nicer
                  ;; to have the session-atom actually be a plain value dict
                  ;; where each of its values happen to be an atom that tracks
                  ;; an individual session.
                  ;; That would reduce the risk of data bleeding between the
                  ;; sessions. And make it cleaner for multiple threads to
                  ;; modify various sessions at the same time.
                  ;; TODO: Experiment with that approach.
                  component (assoc (select-keys session
                                                [::lamport/clock
                                                 ::weald/logger
                                                 ::weald/state-atom
                                                 ::sessions/session-atom])
                                   ;; The extra nesting is an unfortunate leftover
                                   ;; from the original implementation where this
                                   ;; was a Component.
                                   ;; TODO: Unwrap this and eliminate the
                                   ;; backend.event-bus ns.
                                   ;; OTOH...I'm not exactly in love with
                                   ;; manifold's event-bus implementation,
                                   ;; and using that directly would be more
                                   ;; difficult to refactor away.
                                   ::bus/event-bus {::bus/bus event-bus}
                                   ::backend-specs/routes routes
                                   :frereth/session-id session-id)
                  component (handlers/do-connect component session-id)
                  handler (partial handlers/on-message!
                                   component
                                   raw-interceptors)
                  connection-closed (strm/consume handler
                                                  web-socket)]
              (swap! log-state-atom #(log/trace %
                                                ::finalize-login!
                                                "Websocket consumer configured"))
              ;; Cope with it closing
              (dfrd/on-realized connection-closed
                                (fn [succeeded]
                                  (swap! log-state-atom #(log/flush-logs! logger
                                                                          (log/info %
                                                                                    ::finalize-login!
                                                                                    "Websocket closed cleanly"
                                                                                    {:frereth/session-id session-id
                                                                                     ::success succeeded})))
                                  (handlers/disconnect! component session-id)
                                  (swap! session-atom
                                         sessions/disconnect
                                         session-id))
                                (fn [failure]
                                  (swap! log-state-atom #(log/flush-logs! logger
                                                                          (log/info %
                                                                                    ::finalize-login!
                                                                                    "Websocket failed"
                                                                                    {:frereth/session-id session-id
                                                                                     ::success failure})))
                                  (handlers/disconnect! component)
                                  (swap! session-atom
                                         sessions/disconnect
                                         session-id))))
            (catch Exception ex
              (swap! log-state-atom #(log/exception %
                                                    ex
                                                    ::finalize-login!))))
          (do
            (swap! log-state-atom #(log/warn %
                                             ::finalize-login!
                                             "No matching session"
                                             {::sessions/session-state-keys (keys @session-atom)
                                              ::sessions/session-state @session-atom}))
            (throw (ex-info "Browser trying to complete non-pending connection"
                            {::attempt session-id
                             ::sessions/sessions @session-atom})))))
      (swap! log-state-atom #(log/warn %
                                       ::finalize-login!
                                       "Waiting for login completion failed:"
                                       message-wrapper)))
    (finally
      (swap! log-state-atom #(log/flush-logs! logger %)))))

(s/fdef verify-cookie
  :args (s/cat :session-id :frereth/session-id
               :world-id :frereth/world-key)
  :ret boolean?)
(defn verify-cookie
  [actual-session-id
   world-id
   {:keys [:frereth/world-key
           :frereth/session-id]
    :as cookie}]
  ;; TODO: Also need to verify the signature
  (and (= world-key world-id)
       (= session-id actual-session-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef activate-session!
  ;; Q: Should this be a pre-session?
  :args (s/cat :pre-session ::session)
  ;; Called for side-effects
  :ret any?)
(defn activate-session!
  "Browser is trying to initiate a new Session"
  [{lamport-clock ::lamport/clock
    :keys [::bus/event-bus
           ::weald/logger
           ::sessions/session-atom
           ::connection/web-socket]
    log-state-atom ::weald/state-atom
    :as session-wrapper}]
  (swap! log-state-atom #(log/trace %
                                    ::activate-session!
                                    "Top"
                                    {::session (dissoc session-wrapper
                                                       ::weald/state-atom)}))

  (if session-atom
    (try
      ;; FIXME: Better handshake (need an authentication phase)
      (swap! log-state-atom #(log/debug %
                                        ::activate-session!
                                        "Trying to pull the Renderer's key from new websocket"
                                        ;; FIXME: Need a cleaner way to format this for logging
                                        @session-atom))
      ;; Obviously don't want to hard-code this half-second timeout.
      ;; This all ties into that FIXME above about adding a real handshake
      (let [first-message (strm/try-take! web-socket ::drained 500 ::timed-out)]
        ;; TODO: Need the login exchange before this.
        ;; Do that before opening the websocket, using something like SRP.
        ;; Except that people generally agree that it's crap.
        ;; Look into something like OPAQUE instead.
        ;; The consensus seems to be that mutual TLS is really the way
        ;; to go.
        ;; Q: Is there a way to do this for web site auth?
        ;; Q: What about FIDO2?

        ;; That should add the Session's short-term key to the ::pending
        ;; session map.
        ;; In order to authenticate, it had to already contact its Server.
        ;; So it should also have its view of what's going on with this
        ;; Session.
        ;; Honestly, this is mostly a FSM manager for the initial handshake.
        ;; Though passing messages back and forth over the web socket later
        ;; should/will be a much bigger drain on system resources.
        ;; TODO: Check this FSM handshake with stack overflow's security
        ;; board.
        (dfrd/on-realized first-message
                          (partial finalize-login! session-wrapper)
                          (fn [error]
                            (swap! log-state-atom
                                   #(log/exception %
                                                   error
                                                   ::activate-session!)))))
      (catch ExceptionInfo ex
        (swap! log-state-atom
               #(log/exception %
                               ex
                               ::activate-session!
                               "Renderer connection completion failed"))
        (.close web-socket)))
    (do

      (throw (ex-info (str "Missing session-atom")
                      session-wrapper)))))

(s/fdef get-code-for-world
  :args (s/cat :log-state-atom ::weald/state-atom
               :sessions ::sessions/sessions
               :actual-session-id :frereth/session-id
               :world-key :frereth/world-key
               :cookie-bytes bytes?)
  ;; Q: What makes sense for the real return value?
  ;; It's a Response body. So lots.
  ;; handlers depends on this, so we can't use the specs
  ;; defined in there.
  ;; That's another reason to move them.
  :ret (s/nilable #(instance? java.io.File %)))
(defn get-code-for-world
  [log-state-atom sessions actual-session-id world-key cookie-bytes]
  (if actual-session-id
    (if-let [session (sessions/get-active-session sessions
                                                  actual-session-id)]
      ;; decode-cookie gets called during the world-forked interceptor
      ;; in renderer.handlers.
      ;; It seems silly to call it again here.
      ;; FIXME: Eliminate the spare.
      (let [{:keys [:frereth/world-key
                    :frereth/world-ctor]
             expected-session-id :frereth/session-id
             :as cookie} (handlers/decode-cookie log-state-atom cookie-bytes)]
        (swap! log-state-atom
               #(log/trace %
                           ::get-code-for-world
                           "Have a session. Decoded cookie"))
        (if (and world-key expected-session-id world-ctor)
          (if (verify-cookie actual-session-id world-key cookie)
            (do
              (swap! log-state-atom
                     #(log/info %
                                ::get-code-for-world
                                "Cookie verified"))
              (let [opener (if (.exists (io/file "dev-output/js"))
                             io/file
                             (fn [file-name]
                               (io/file (io/resource (str "js/" file-name)))))
                    ;; FIXME: This needs to vary based on the World that's
                    ;; actually being connected
                    raw (opener "dev-output/js/worker.js")]
                (when-not (.exists raw)
                  (throw (ex-info "Missing worker file"
                                  {::problem opener})))
                ;; I still need access to the actual worker .cljs so
                ;; I can inject the public key that must be part of the cookie.
                ;; This is really just the .js that loads up that .cljs.
                ;; Though, honestly, this needs to adjust all the calls to
                ;; require to place them under an API route that involves
                ;; both the session and world IDs.
                (swap! log-state-atom
                       #(log/trace %
                                   ::get-code-for-world
                                   "Returning"
                                   {::code-file raw
                                    ::code-file-type (type raw)}))
                raw))
            (do
              (swap! log-state-atom
                     #(log/error %
                                 ::get-code-for-world
                                 "Bad Initiate packet"
                                 {:frereth/cookie cookie
                                  :frereth/world-key world-key
                                  :actual/session-id actual-session-id
                                  :expected/session-id expected-session-id}))
              (throw (ex-info "Invalid Cookie: probable hacking attempt"
                              {:frereth/session-id expected-session-id
                               ::real-session-id actual-session-id
                               :frereth/world-key world-key
                               :frereth/cookie cookie}))))
          (do
            (swap! log-state-atom
                   #(log/error %
                               ::get-code-for-world
                               "Incoming cookie has issue with one of:"
                               {:frereth/world-key world-key
                                :frereth/session-id expected-session-id
                                :frereth/world-ctor world-ctor
                                ::cookie cookie}))
            (throw (ex-info "Bad cookie"
                            cookie)))))
      (do
        (swap! log-state-atom
               #(log/error %
                           ::get-code-for-world
                           "Missing session key"
                           {:frereth/session-id actual-session-id
                            ::session-id-type (type actual-session-id)
                            ::sessions/active (reduce (fn [acc session-key]
                                                        (assoc acc session-key (type session-key)))
                                                      (sessions/get-by-state sessions ::sessions/active))}))
        (throw (ex-info "Trying to fork World for missing session"
                        {::sessions/sessions sessions
                         :frereth/session-id actual-session-id
                         :frereth/world-key world-key
                         ::cookie-bytes cookie-bytes}))))
    (throw (ex-info "Trying to fork World with falsey session-id"
                    {::sessions/sessions sessions
                     :frereth/session-id actual-session-id
                     :frereth/world-key world-key
                     ::cookie-bytes cookie-bytes}))))

(s/fdef register-pending-world!
  :args (s/cat :session-atom ::sessions/session-atom
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :cookie ::cookie))
(defn register-pending-world!
  "Browser has requested a World's Code. Time to take things seriously"
  [session-atom session-id world-key cookie]
  (swap! session-atom
         sessions/add-pending-world
         session-id world-key cookie))
