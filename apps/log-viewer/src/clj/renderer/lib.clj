(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require
   [backend.event-bus :as bus]
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
   [manifold
    [deferred :as dfrd]
    [stream :as strm]]
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

(s/fdef login-finalized!
  :args (s/cat :session ::session
               :wrapper string?)
  :ret any?)
(defn login-finalized!
  "Client might have authenticated over websocket"
  [{lamport ::lamport/clock
    :keys [::bus/event-bus
           ::weald/logger
           ::sessions/session-atom
           ::connection/web-socket]
    log-state-atom ::weald/state-atom
    :as session}
   ;; FIXME: refactor-rename this to message-wrapper
   ;; or something along those lines
   wrapper]
  (swap! log-state-atom
         #(log/info %
                    ::login-finalized!
                    "Initial websocket message"
                    {::message wrapper}))
  (if (and (not= ::drained wrapper)
           (not= ::timed-out wrapper))
    (let [envelope (serial/deserialize wrapper)
          {{:keys [:frereth/session-id]
            :as params} :params
           :as request} (:request envelope)]
      (swap! log-state-atom
             #(log/debug %
                         ::login-finalized!
                         "Key pulled"
                         {::deserialized envelope
                          ::params params}))
      (try
        (let [session-state (sessions/get-by-state @session-atom
                                                   ::connection/pending)]
          (swap! log-state-atom
                 #(log/debug %
                             ::login-finalized!
                             "Trying to activate session"
                             {::connection/session-id session-id
                              ::session-id-type (type session-id)
                              ::sessions/session session-state})))
        (catch Exception ex
          (swap! log-state-atom #(log/flush-logs! logger
                                                  (log/exception %
                                                                 ex
                                                                 ::login-finalized!
                                                                 "Failed trying to log activation details"
                                                             {::connection/session-id session-id
                                                              ::sessions/session-atom session-atom})))))
      (if (get @session-atom session-id)
        (try
          (swap! log-state-atom #(log/flush-logs! logger
                                                  (log/debug %
                                                             ::login-finalized!
                                                             "Activating session"
                                                             {::connection/session-id session-id})))
          ;; FIXME: Don't particularly want the session-atom in here.
          (swap! session-atom
                 (fn [sessions]
                   (update sessions session-id
                           connection/activate
                           web-socket)))
          (swap! log-state-atom #(log/flush-logs! logger
                                                  (log/debug %
                                                             ::login-finalized!
                                                             "Swapped:"
                                                             {::connection/web-socket web-socket})))
          ;; Set up the message handler
          (let [routes (handlers/build-routes)  ; FIXME: move this up the chain
                connection-closed
                (strm/consume (partial handlers/on-message!
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
                                       ;; pick up state changes
                                       (assoc (select-keys session
                                                           [::bus/event-bus
                                                            ::lamport/clock
                                                            ::weald/logger
                                                            ::weald/state-atom
                                                            ::sessions/session-atom])
                                              ::handlers/routes routes
                                              ::connection/session-id session-id))
                              web-socket)]
            (swap! log-state-atom #(log/flush-logs! logger
                                                    (log/trace %
                                                               ::login-finalized!
                                                               "Set up websocket consumer")))
            ;; Cope with it closing
            (dfrd/on-realized connection-closed
                              (fn [succeeded]
                                (println (str ::login-finalized!
                                              " Socket closed cleanly"
                                              " for session "
                                              session-id
                                              ": "
                                              succeeded))
                                (swap! session-atom
                                       sessions/disconnect
                                       session-id))
                              (fn [failure]
                                (println ::login-finalized!
                                         "Web socket failed for session"
                                         session-id
                                         ":"
                                         failure)
                                (swap! session-atom
                                       sessions/disconnect
                                       session-id))))
          (catch Exception ex
            (swap! log-state-atom #(log/flush-logs! logger
                                                    (log/exception %
                                                                   ex
                                                                   ::login-finalized!)))))
        (do
          (swap! log-state-atom #(log/flush-logs! logger
                                                  (log/warn %
                                                            ::login-finalized!
                                                            "No matching session"
                                                            {::sessions/session-state-keys (keys @session-atom)
                                                             ::sessions/session-state @session-atom})))
          (throw (ex-info "Browser trying to complete non-pending connection"
                          {::attempt session-id
                           ::sessions/sessions @session-atom})))))
    (swap! log-state-atom #(log/flush-logs! logger
                                            (log/warn %
                                                      ::login-finalized!
                                                      "Waiting for login completion failed:"
                                                      wrapper)))))

(s/fdef verify-cookie
  :args (s/cat :session-id :frereth/session-id
               :world-id :frereth/world-key)
  :ret boolean?)
(defn verify-cookie
  [actual-session-id
   world-id
   {:keys [:frereth/pid
           :frereth/session-id]
    :as cookie}]
  ;; TODO: Need to verify the cookie plus its signature
  (and (= pid world-id)
       (= session-id actual-session-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef activate-session!
  :args (s/cat :pre-session ::session)
  ;; Called for side-effects
  :ret any?)
(defn activate-session!
  "Browser is trying to initiate a new Session"
  [{lamport-clock ::lamport/clock
    :keys [::bus/event-bus
           ::sessions/session-atom
           ::connection/web-socket]
    :as session}]
  (println ::activate-session! "session-atom:"
           session-atom "\namong\n"
           (cp-util/pretty session))

  (if session-atom
    (try
      ;; FIXME: Better handshake (need an authentication phase)
      (println ::activate-session!
               "Trying to pull the Renderer's key from new websocket"
               "\namong"
               @session-atom)
      (let [first-message (strm/try-take! web-socket ::drained 500 ::timed-out)]
        ;; TODO: Need the login exchange before this.
        ;; Do that before opening the websocket, using something like SRP.
        ;; Except that people generally agree that it's crap.
        ;; Look into something like OPAQUE instead.
        ;; The consensus seems to be that mutual TLS is really the way
        ;; to go.
        ;; Q: Is there a way to do this for web site auth?
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
                          (partial login-finalized! session)
                          (fn [error]
                            (println ::activate-session!
                                     "Failed pulling initial key:" error))))
      (catch ExceptionInfo ex
        ;; FIXME: Better error handling via tap>
        ;; As ironic as that seems
        (println ::activate-session!
                 "Renderer connection completion failed")
        (pprint ex)
        (.close web-socket)))
    (do

      (throw (ex-info (str "Missing session-atom")
                      session)))))

(s/fdef get-code-for-world
  :args (s/cat :sessions ::sessions/sessions
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
  [sessions actual-session-id world-key cookie-bytes]
  (if actual-session-id
    (if-let [session (sessions/get-active-session sessions
                                                  actual-session-id)]
      ;; decode-cookie gets called during the world-forked interceptor
      ;; in renderer.handlers.
      ;; It seems silly to call it again here.
      ;; FIXME: Eliminate the spare.
      (let [{:keys [:frereth/pid
                    :frereth/world-ctor]
             expected-session-id :frereth/session-id
             :as cookie} (handlers/decode-cookie cookie-bytes)]
        (println ::get-code-for-world "Have a session. Decoded cookie")
        (if (and pid expected-session-id world-ctor)
          (if (verify-cookie actual-session-id world-key cookie)
            (do
              (println ::get-code-for-world "Cookie verified")
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
                (println ::get-code-for-world "Returning" raw "a" (class raw))
                raw))
            (do
              (println "Bad Initiate packet.\n"
                       cookie "!=" world-key
                       "\nor"
                       actual-session-id "!=" expected-session-id)
              (throw (ex-info "Invalid Cookie: probable hacking attempt"
                              {:frereth/session-id expected-session-id
                               ::real-session-id actual-session-id
                               :frereth/world-key world-key
                               :frereth/cookie cookie}))))
          (do
            (println (str "Incoming cookie has issue with either '"
                          pid
                          "', '"
                          expected-session-id
                          "', or '"
                          world-ctor
                          "'"))
            (throw (ex-info "Bad cookie"
                            cookie)))))
      (do
        (println "Missing session key\n"
                 actual-session-id "a" (class actual-session-id)
                 "\namong")
        (doseq [session-key (sessions/get-by-state sessions ::sessions/active)]
          (println session-key "a" (class session-key)))
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
