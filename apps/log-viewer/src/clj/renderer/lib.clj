(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require
   [backend.event-bus :as bus]
   [client.registrar :as registrar]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.world :as world]
   [frereth.cp.shared.crypto :as crypto]
   [io.pedestal.http.route :as route]
   [manifold
    [deferred :as dfrd]
    [stream :as strm]]
   [clojure.java.io :as io]
   [clojure.core.async :as async]
   [frereth.apps.shared.connection :as connection]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.specs]
   [frereth.apps.shared.serialization :as serial]
   [renderer.sessions :as sessions]
   [frereth.weald.logging :as log]
   [io.pedestal.interceptor :as intc]
   [io.pedestal.interceptor.chain :as intc-chain])
  (:import clojure.lang.ExceptionInfo
           java.util.Base64))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; This is a serializable value that will get converted to travel
;; across a wire.
(s/def :frereth/body any?)

(s/def ::cookie (s/keys :req [:frereth/pid
                              :frereth/state
                              :frereth/world-ctor]))

;; Note that this assumes messages go to a specific world.
;; Q: How valid is that assumption?
(s/def ::message-envelope (s/keys :req [:frereth/action
                                        :frereth/body
                                        :frereth/lamport
                                        :frereth/world-key]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

;; FIXME: This seems like it might be more generally useful for other
;; renderers.
;; Then again, any such hypothetical renderers will be built around a
;; totally different architecture, so probably not.
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

;; Needing to do this smells.
;; This means it should move into its own ns.
;; Then again...so should pretty much everything else in here.
(declare post-message!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Interceptors

(def request-deserializer
  "Convert raw message string into a clojure map"
  {:name ::request-deserializer
   :enter (fn [context]
            (update context :request
                    (fn [request]
                      (let [{:keys [:frereth/body]
                             :as result} (serial/deserialize request)]
                        (println (str ::request-deserializer
                                      " handling\n"
                                      body
                                      ", a " (type body)))
                        result))))})

(s/fdef build-ticker
  :args (s/cat :clock ::lamport/clock)
  ;; This returns an interceptor.
  ;; FIXME: track down that definition
  :ret any?)
(defn build-ticker
  "Update the Lamport clock"
  [clock]
  {:name ::lamport-ticker
   :enter (fn [{{remote-clock :frereth/lamport} :request
                :as context}]
            ;; Q: Do I want to handle it this way?
            ;; (it associates the current clock-tick, rather
            ;; than an actual clock)
            (update-in context [:request :frereth/lamport]
                       (partial lamport/do-tick clock)))})

(def session-extractor
  "Pull the session out of the session atom"
  {:name ::session-active?
   :enter (fn [{:keys [::lamport/clock
                       ::sessions/session-atom
                       ::connection/session-id]
                :as context}]
            ;; It shouldn't be possible to get here if the session isn't
            ;; active.
            ;; It seems worth checking for that scenario out of paranoia.
            (if-let [session (sessions/get-active-session @session-atom session-id)]
              (-> context
                  ;; Interceptors that need the session-id can get it
                  ;; from the session
                  (dissoc ::sessions/session-atom ::sessions/session-id)
                  (assoc :frereth/session session))
              (do
                (lamport/do-tick clock)
                (throw (ex-info "Websocket message for inactive session. This should be impossible"
                                (assoc context
                                       ::sessions/active (keys (sessions/get-by-state @session-atom
                                                                                      ::sessions/active))
                                       ::sessions/pending (sessions/get-by-state @session-atom
                                                                                 ::sessions/pending)))))))})

(defn handle-world-message!
  [{:keys [::bus/event-bus
           :request
           :frereth/session]
    lamport ::lamport/clock
    :as context}
   raw-message]
  (let [{:keys [::connection/session-id
                :frereth/world-key
                ::world-stop-signal]} session]
    (throw (RuntimeException. "Still not quite there"))
    (if (not= raw-message world-stop-signal)
      ;; TODO: This should be a call to bus/publish!
      (post-message! session
                     lamport
                     world-key
                     :frereth/forward
                     raw-message)
      (do
        ;; It seems like it would be wiser to have the
        ;; deactivate-world handler trigger this
        ;; post-message!.
        (post-message! session
                       lamport
                       world-key
                       :frereth/disconnect
                       true)
        (bus/publish! event-bus ::deactivate-world
                      (select-keys session
                                   [::connection/session-id
                                    :frereth/world-key]))))))
(def world-forked
  {:name ::world-forked
   :enter (fn [{{:keys [::connection/session-id
                        :frereth/worlds]
                 :as session} :frereth/session
                lamport ::lamport/clock
                request :request
                :as context}]
            (let [{:keys [:params]} request
                  {world-key :frereth/pid
                   :keys [:frereth/cookie]} params]
              ;; Once the browser has its worker up and running, we need to start
              ;; interacting.
              ;; Actually, there's an entire lifecycle here.
              ;; It's probably worth contemplating the way React handles the entire
              ;; idea (though it may not fit at all).
              ;; Main point:
              ;; This needs to start up a new System of Component(s) that
              ;; :frereth/forking prepped.
              (if-let [world (world/get-pending worlds world-key)]
                (let [{actual-pid :frereth/pid  ; TODO: Refactor/rename this to :frereth/world-key
                       actual-session :frereth/session-id
                       constructor-key :frereth/world-ctor
                       :as decrypted} (decode-cookie cookie)]
                  (if (and (= session-id actual-session)
                           (= world-key actual-pid))
                    (let [connector (registrar/lookup session-id constructor-key)
                          world-stop-signal (gensym "frereth.stop.")
                          client (connector world-stop-signal
                                            ;; FIXME: Refactor this into its own
                                            ;; top-level function to reduce some of
                                            ;; the noise in here.
                                            ;; Q: Where should that top-level function
                                            ;; live? (that isn't rhetorical)
                                            (partial handle-world-message! (assoc context
                                                                                  :frereth/world-key world-key
                                                                                  ::world-stop-signal world-stop-signal)))]
                      ;; TODO: publish! this message to the event bus.
                      ;; Its handler should know enough to handle both
                      ;; the post-message! and call to
                      ;; activate-forking-world
                      (throw (RuntimeException. "Nope"))
                      (post-message! session
                                     lamport
                                     world-key
                                     :frereth/ack-forked)
                      ;; This needs to post another message instead
                      ;; Better alt: Have a second subscriber on an internal
                      ;; message bus that listens for
                      ;; the :frereth/ack-forked.
                      (post-message! session
                                     lamport
                                     world-key)
                      {:response #(sessions/activate-forking-world sessions
                                                                   session-id
                                                                   world-key
                                                                   client)})
                    ;; This could be a misbehaving World.
                    ;; Or it could be a nasty bug in the rendering code.
                    ;; Well, it would have to be a nasty bug for a World to misbehave
                    ;; this way.
                    ;; At this point, we should probably assume that the browser is
                    ;; completely compromised and take drastic measures.
                    ;; Notifying the user and closing the websocket to end the session
                    ;; might be a reasonable start.
                    (let [error-message
                          (str "session/world mismatch\n"
                               (cond
                                 (not= session-id actual-session) (str session-id
                                                                       ", a "
                                                                       (class session-id)
                                                                       " != "
                                                                       actual-session
                                                                       ", a "
                                                                       (class actual-session))
                                 :else (str world-key ", a " (class world-key)
                                            " != "
                                            actual-pid ", a " (class actual-pid)))
                               "\nQ: notify browser?")]
                      (println error-message)
                      sessions)))
                (do
                  (println "Need to make world lookup simpler. Could not find world")
                  (pprint {::active-sessions (sessions/get-by-state sessions
                                                                    ::sessions/active)
                           ::among params
                           ::world-key world-key
                           ::world-key-class (class world-key)})
                  sessions))))})

(def world-forking
  {:name ::world-forking
   :enter (fn [{:keys []
                lamport ::lamport/clock
                :as context}
               sessions

               session-id
               {:keys [:frereth/command
                       ;; It seems like it would be better to make this explicitly a
                       ;; :frereth/world-key instead.
                       :frereth/pid]}]
            (if (and command pid)
              (when-let [session (sessions/get-active-session sessions session-id)]
                (if-not (connection/get-world session pid)
                  ;; TODO: Need to check with the Client registry
                  ;; to verify that command is legal for the current
                  ;; session (at the very least, this means authorization)
                  (let [cookie (build-cookie session-id pid command)]
                    (try
                      (post-message! sessions
                                     lamport
                                     session-id
                                     pid
                                     :frereth/ack-forking
                                     {:frereth/cookie cookie})
                      (catch Exception ex
                        (println "Error:" ex
                                 "\nTrying to post :frereth/ack-forking to"
                                 session-id
                                 "\nabout"
                                 pid)))
                    ;; It's tempting to add the world to the session here.
                    ;; Actually, there doesn't seem like any good reason
                    ;; not to (and not earlier)
                    ;; There are lots of places for things to break along
                    ;; the way, but this is an important starting point.
                    ;; It's also very tempting to set up an Actor model
                    ;; sort of thing inside the Session to handle this
                    ;; World that we're trying to create.
                    ;; This makes me think this this is working at too high
                    ;; a layer in the abstraction tree.
                    ;; We should really be running something like update-in
                    ;; with just the piece that's being changed here (in
                    ;; this case, the appropriate Session).
                    sessions)
                  (do
                    (println "Error: trying to re-fork pid" pid
                             "\namong\n"
                             sessions)
                    sessions)))
              (do
                (println (str "Missing either/both of '"
                              command
                              "' or/and '"
                              pid
                              "'"))
                sessions)))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef do-wrap-message
  :args (s/or :with-body (s/cat :lamport ::lamport/clock
                                :world-key :frereth/world-key
                                :action :frereth/action
                                :value :frereth/body)
              :sans-body (s/cat :lamport ::lamport/clock
                                :world-key :frereth/world-key
                                :action :frereth/action))
  :ret ::message-envelope)
;; Honestly, this should be a Component in the System.
;; And it needs to interact with the clock from weald.
;; Interestingly enough, this makes it tempting to convert both
;; do-wrap-message and on-message! into a Protocol.
(defn do-wrap-message
  ([lamport world-key action]
   (println "Building a" action "message")
   (swap! lamport inc)
   {:frereth/action action
    :frereth/lamport @lamport
    :frereth/world-key world-key})
  ([lamport world-key action value]
   (let [result
         (assoc (do-wrap-message lamport world-key action)
                :frereth/body value)]
     (println "Adding" value "to message")
     result)))

(s/fdef on-message!
  :args (s/cat :session-atom ::sessions/session-atom
               :session-id ::sessions/session-id
               :clock ::lamport/clock
               ;; FIXME: This needs an ::event-bus parameter
               ;; also
               :message-string string?)
  ;; Called for side-effects
  :ret any?)
(defn on-message!
  "Deserialize and dispatch a raw message from browser"
  ;; This consumes messages from the websocket associated
  ;; with public-key until that websocket closes.
  [session-atom session-id clock  message-string]
  ;; STARTED: Break this up into a Pedestal interceptor chain
  (try
    ;; TODO: Build the terminators/interceptors/routers elsewhere.
    ;; It's very tempting to make the routes part of the specific
    ;; session.
    ;; Need something equivalent to create-server or create-servlet
    ;; to set up the router.
    ;; Don't want to use either of those, because it injects far
    ;; too many interceptors that just don't fit here.
    (let [terminators #{}  ; Q: Is there anything useful to put in here?
          ;; FIXME: need route definitions
          ;; Also need an option, especially in debug mode, for this
          ;; to be function that gets called each time.
          routes #{["/" :frereth/forked world-forked]
                   ["/" :frereth/forking world-forking]}
          processed-routes (route/expand-routes routes)
          raw-interceptors [session-extractor
                            request-deserializer
                            (build-ticker clock)
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
                            ;; For now, just skip the idea of url-params.
                            route/query-params
                            ;; It might make sense to parameterize this,
                            ;; but it doesn't seem likely.
                            ;; If I do add the concept of a URI path
                            ;; here, I don't see adding in path params.
                            ;; This dispatch needs to happen fast.
                            (route/router processed-routes :map-tree)]
          ;; This isn't an HTTP request handler, so the details are
          ;; a little different.
          ;; Especially: use the :action as the request's :verb
          context {::lamport/clock clock
                   :request message-string
                   ;; Putting the session atom into here
                   ;; seems like a bad idea.
                   ;; dispatch currently pulls out the actual
                   ;; session, if needed.
                   ;; It seems wiser to extract it first and
                   ;; pass the specific session value to the
                   ;; handler.
                   ;; That way there's no risk of cross-
                   ;; pollination.
                   ;; At the same time...realistically, this
                   ;; should really start by restricting the
                   ;; message to a specific world.
                   ;; TODO: Think about locking this down
                   ;; thoroughly.
                   ;; But I need to get it working before I
                   ;; worry about the next round of cleanup.
                   ::sessions/session-atom session-atom
                   ::sessions/session-id session-id
                   ::intc-chain/terminators terminators}
          context (intc-chain/enqueue (map intc/interceptor
                                           raw-interceptors))]
      ;; The actual point.
      ;; It's easy to miss this in the middle of the error handling.
      ;; Which is one reason this is worth keeping separate from the
      ;; dispatching code.
      ;; Doing this inside a swap! seems iffy.
      ;; Realistically, dispatch should return something like a
      ;; tuple of:
      ;; a) the new state
      ;; b) seq of functions to call to trigger side-effects
      ;; That isn't quite right. The side-effecting functions
      ;; could result in a different end state for this...call
      ;; it a transaction.
      ;; But it's better than having a big, synchronous
      ;; transformation that seems very likely to either block
      ;; updates or trigger multiple side-effects.
      ;; (Q: Isn't it?)
      ;; TODO: Review how atoms really work. Especially in terms
      ;; of conflict resolution.
      ;; Seriously. Get back to this.
      (swap! session-atom dispatch clock session-id message-string)
      (let [{:keys [:response]
             :as context} (intc-chain/execute context)]
        (if response
          (throw (RuntimeException. "Need to swap! that response into the correct session"))
          (println "Unhandled action:" message-string))))
    (catch Exception ex
      (println ex "trying to deserialize/dispatch" message-string))))

(s/fdef build-cookie
  :args (s/cat :session-id ::session-id
               :world-key ::world-key
               :command :frereth/command)
  :ret bytes?)
(defn build-cookie
  [session-id
   world-key
   command]
  ;; This approach seems overly complex, but setting up more state
  ;; is dangerous and easily exploited.
  ;; The fact that the client is authenticated helps with post-mortems, but
  ;; we should try to avoid those.
  ;; We also need some sort of throttle on forks per second and/or pending
  ;; forks.

  ;; Take a page from the CurveCP handshake. Use a
  ;; minute-cookie for encryption.
  ;; When the client notifies us that it has forked, we can
  ;; decrypt the cookie and mark this World active for the
  ;; appropriate SESSION.
  ;; Q: Would it be worthwhile to add another layer to this?
  ;; Have the browser query for the worker code. We use this
  ;; cookie to inject another short-term cookie key into that
  ;; worker code.
  ;; Then the ::forked handler could verify them all.
  ;; It seems like we really have to do something along those
  ;; lines, since we cannot possibly trust the browser and the
  ;; HTTP request could come from anywhere.
  ;; A malicious client could still share the client's private
  ;; key and request a billion copies of the browser page.
  ;; Then again, that seems like a weakness in CurveCP also.
  (let [dscr {:frereth/pid world-key
              :frereth/session-id session-id
              :frereth/world-ctor command}
        world-system-bytes (serial/serialize dscr)]
    ;; TODO: This needs to be encrypted by the current minute
    ;; key before we encode it.
    ;; Q: Is it worth keeping a copy of the encoder around persistently?
    (.encode (Base64/getEncoder) world-system-bytes)))

(s/fdef decode-cookie
  :args (s/cat :cookie-bytes bytes?)
  :ret ::cookie)
(defn decode-cookie
  [cookie-bytes]
  (println (str "Trying to get the cookie bytes from "
                cookie-bytes
                ", a " (type cookie-bytes)))
  (let [cookie-bytes (.decode (Base64/getDecoder) cookie-bytes)
        cookie-string (String. cookie-bytes)]
    (println "Trying to decode" cookie-string "a"
             (class cookie-string)
             "from"
             cookie-bytes "a" (class cookie-bytes))
    (serial/deserialize cookie-string)))

(s/fdef post-real-message!
  :args (s/cat :session ::sessions/session
               :world-key :frereth/world-key
               ;; This needs to be anything that we can cleanly
               ;; serialize
               :wrapper any?)
  :ret any?)
(defn post-real-message!
  "Forward serialized value to the associated World"
  [{:keys [::connection/session-id
           ::connection/state
           ::connection/web-socket]
    :as session} world-key wrapper]
  (println (str "Trying to send "
                wrapper
                "\nto "
                world-key
                "\nin\n"
                session-id))
  ;; Q: Would calling post! to an inactive session really be so
  ;; terrible?
  ;; A: Maybe not. But it doesn't make any sense.
  (if (= ::connection/active state)
    (try
      (let [envelope (serial/serialize wrapper)]
        (try
          (let [success (strm/try-put! web-socket
                                       envelope
                                       500
                                       ::timed-out)]
            ;; FIXME: Add context about what we just tried to
            ;; forward
            (dfrd/on-realized success
                              #(println "Forwarded:" %)
                              #(println "Forwarding failed:" %)))
          (catch Exception ex
            (println "Message forwarding failed:" ex))))
      (catch Exception ex
        (println "Serializing message failed:" ex)))
    (do
      (println "Session not active")
      (throw (ex-info "Trying to POST to inactive Session"
                      {::connection/session session
                       :frereth/world-key world-key})))))

(s/fdef post-message!
  :args (s/or :with-value (s/cat :session :frereth/session
                                 :lamport ::lamport/clock
                                 :world-key :frereth/world-key
                                 :action :frereth/action
                                 :value :frereth/body)
              :sans-value (s/cat :session :frereth/session
                                 :lamport ::lamport/clock
                                 :world-key :frereth/world-key
                                 :action :frereth/action))
  :ret any?)
(defn post-message!
  "Marshalling wrapper around post-real-message!"
  ([session lamport world-key action value]
   (println ::post-message! " with value " value)
   (let [wrapper (do-wrap-message lamport world-key action value)]
     (post-real-message! session world-key wrapper)))
  ([session lamport world-key action]
   (println ::post-message! " without value")
   (let [wrapper (do-wrap-message lamport world-key action)]
     (post-real-message! session world-key wrapper))))

(defmethod dispatch :default
  [sessions
   lamport
   session-id
   body]

  sessions)

(s/fdef login-finalized!
  :args (s/cat :lamport ::lamport/clock
               :session-atom ::sessions/session-atom
               :websocket ::connection/web-socket
               :wrapper string?)
  :ret any?)
(defn login-finalized!
  "Client might have authenticated over websocket"
  [lamport session-atom websocket wrapper]
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
          (swap! session-atom
                 (fn [sessions]
                   (update sessions session-id
                           connection/activate
                           websocket)))
          (println ::login-finalized! "Swapped:")
          (pprint websocket)
          ;; Set up the message handler
          (let [connection-closed
                (strm/consume (partial on-message!
                                       ;; This is another opportunity to
                                       ;; learn from om-next.
                                       ;; Possibly.
                                       ;; TODO: Review how it's replaced
                                       ;; Om's cursors.
                                       ;; That's really more relevant
                                       ;; for the world-state.
                                       session-atom
                                       session-id
                                       lamport)
                              websocket)]
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
  :args (s/cat :lamport-clock ::lamport/clock
               :session-atom ::sessions/session-atom
               :connection (s/and strm/sink?
                                  strm/source?)))
(defn activate-session!
  "Browser is trying to initiate a new Session"
  [lamport-clock session-atom websocket]
  (try
    ;; FIXME: Better handshake (need an authentication phase)
    (println ::activate-session!
             "Trying to pull the Renderer's key from new websocket"
             "\namong"
             @session-atom)
    (let [first-message (strm/try-take! websocket ::drained 500 ::timed-out)]
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
                        (partial login-finalized!
                                 lamport-clock
                                 session-atom
                                 websocket)
                        (fn [error]
                          (println ::activate-session!
                                   "Failed pulling initial key:" error))))
    (catch ExceptionInfo ex
      ;; FIXME: Better error handling via tap>
      ;; As ironic as that seems
      (println ::activate-session!
               "Renderer connection completion failed")
      (pprint ex)
      (.close websocket))))

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
