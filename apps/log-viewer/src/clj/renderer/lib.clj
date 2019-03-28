(ns renderer.lib
  "Library functions specific for the web renderer"
;; FIXME: A lot of this (esp. the on-message! chain) seems like it might
;; be more generally useful for other renderers.
;; Then again, any such hypothetical renderers will be built around a
;; totally different architecture, so probably not.
  (:require
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.world :as world]
   [frereth.cp.shared.crypto :as crypto]
   [frereth.apps.shared.connection :as connection]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.specs]
   [frereth.apps.shared.serialization :as serial]
   [frereth.weald.logging :as log]
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
                                   ::sessions/session-atom
                                   ::connection/web-socket]))

(s/def ::context map?)
;; Q: Right?
(s/def ::terminator (s/fspec :args (s/cat :context ::context)
                             :ret boolean?))
(s/def ::terminators (s/coll-of ::terminator))
(s/def ::session (s/keys :req [::terminators]))

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

(s/fdef dispatch
  :args (s/cat :session ::sessions/sessions
               :lamport ::lamport/clock
               :session-id ::sessions/session-id
               :body :frereth/body)
  ;; Q: What should this spec?
  ;; The return value of the defmulti dispatcher?
  ;; Or the actual multi-method?
  ;; Currently, defining a spec on a multimethod doesn't seem to be
  ;; supported at all.
  :ret ::sessions/sessions)
(defmulti dispatch
  "Browser sent message to a World associated with session-id"
  ;; Probably worth mentioning that this is mainly for the sake of
  ;; calling swap! on a session atom
  (fn [session  ; ::sessions/sessions
       lamport  ; ::lamport/clock
       session-id  ; ::sessions/session-id
       {:keys [:frereth/action]
        :as body}]
    ;; FIXME: This should go away
    action))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defmethod dispatch :default
  [sessions
   lamport
   session-id
   body]

  sessions)

(s/fdef login-finalized!
  :args (s/cat :session ::session
               :wrapper string?)
  :ret any?)
(defn login-finalized!
  "Client might have authenticated over websocket"
  [{lamport ::lamport/clock
    :keys [::sessions/session-atom ::connection/web-socket]} wrapper]
  (println ::login-finalized! "Received initial websocket message:" wrapper)
  (if (and (not= ::drained wrapper)
           (not= ::timed-out wrapper))
    (let [envelope (serial/deserialize wrapper)
          _ (println ::login-finalized! "Key pulled:" envelope)
          session-id (:frereth/body envelope)]
      (try
        (println ::login-finalized! "Trying to activate\n" session-id
                 "a" (class session-id)
                 "\nfrom\n"
                 (sessions/get-by-state @session-atom ::connection/pending))
        (catch Exception ex
          (println "Failed trying to log activation details:" ex)
          (pprint {::details (log/exception-details ex)
                   ::sessions/session-id session-id
                   ::session-atom session-atom})))
      (if (get @session-atom session-id)
        (do
          (println ::login-finalized!
                   "Activating session"
                   session-id)
          ;; FIXME: Don't particularly want the session-atom in here.
          (swap! session-atom
                 (fn [sessions]
                   (update sessions session-id
                           connection/activate
                           web-socket)))
          (println ::login-finalized! "Swapped:")
          (pprint web-socket)
          ;; Set up the message handler
          (let [connection-closed
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
                                       session-atom
                                       session-id
                                       lamport)
                              web-socket)]
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
                                       session-id)))))
        (do
          (println ::login-finalized! "No match for"
                   session-id
                   "\namong\n"
                   (keys @session-atom)
                   "\nin\n"
                   @session-atom)
          (throw (ex-info "Browser trying to complete non-pending connection"
                          {::attempt session-id
                           ::sessions/sessions @session-atom})))))
    (println ::login-finalized!
             "Waiting for login completion failed:"
             wrapper)))

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
    :keys [::sessions/session-atom ::connection/web-socket]
    :as session}]
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
      (.close web-socket))))

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
      (let [{:keys [:frereth/pid
                    :frereth/world-ctor]
             expected-session-id :frereth/session-id
             :as cookie} (decode-cookie cookie-bytes)]
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
