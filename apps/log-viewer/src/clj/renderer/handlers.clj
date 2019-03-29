(ns renderer.handlers
  "Handlers for renderer messages"
  (:require
   [backend.event-bus :as bus]
   [client.registrar :as registrar]
   [clojure.pprint :refer (pprint)]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.connection :as connection]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.world :as world]
   [integrant.core :as ig]
   [io.pedestal.interceptor :as intc]
   [io.pedestal.interceptor.chain :as intc-chain]
   [io.pedestal.http.route :as route]
   [manifold.deferred :as dfrd]
   [manifold.stream :as strm]
   [renderer.sessions :as sessions])
  (:import java.util.Base64))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::cookie (s/keys :req [:frereth/pid
                              :frereth/state
                              :frereth/world-ctor]))

;; Note that this assumes messages go to a specific world.
;; Q: How valid is that assumption?
(s/def ::message-envelope (s/keys :req [:frereth/action
                                        :frereth/body
                                        :frereth/lamport
                                        :frereth/world-key]))

(s/def ::world-connection (s/keys :req [::bus/event-bus
                                        ::lamport/clock
                                        ::connection/session-id
                                        :frereth/world-key
                                        ::world-stop-signal]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Helpers

(s/fdef build-cookie
  :args (s/cat :session-id ::sessions/session-id
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

(s/fdef connect-handler
  :args (s/cat :event-bus ::bus/event-bus
               :signal keyword?
               :handler (s/fspec :args (s/cat :message any?)
                                 :ret any?))
  :ret strm/stream?)
(defn connect-handler
  [event-bus signal handler]
  (let [stream (bus/subscribe event-bus signal)]
    (strm/consume handler stream)
    stream))

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

(s/fdef post-real-message!
  :args (s/cat :session ::sessions/session
               :world-key :frereth/world-key
               ;; This needs to be anything that we can cleanly
               ;; serialize
               :wrapper any?)
  :ret any?)
(defn post-real-message!
  ;; TODO: Move this elsewhere.
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

;;; I'm not convinced this belongs in here.
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Privileged Handlers
;;;; Subscribed to current System's event-bus
;;;; These have access to things like the session atom
;;;; These probably belong in a generic frereth.apps.lib workspace.

;;;; It's tempting to write a macro to generate these and the
;;;; integrant methods.
;;;; That's doable but seems dubious. We really need a data
;;;; structure that maps the key names to function bodies
;;;; et al.
;;;; Then we could process those to generate the functions and
;;;; methods we need.

(defn handle-disconnected
  [{:keys [::sessions/session-atom]}
   {:keys [::connection/session-id
           :frereth/world-key]}]
  (swap! session-atom
         #(sessions/deactivate-world % session-id world-key)))

(defn handle-forked
  [{:keys [::sessions/session-atom]}
   {:keys [::client
           ::lamport/clock
           :frereth/world-key]
    {:keys [::sessions/session-id]
     :as session} ::connection/session
    :as message}]
  ;; This needs to post another message instead
  ;; Better alt: Have a second subscriber on an internal
  ;; message bus that listens for
  ;; the :frereth/ack-forked.
  (post-message! session
                 clock
                 world-key
                 :frereth/ack-forked)
  (sessions/activate-forking-world session-atom
                                   session-id
                                   world-key
                                   client))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Outer Handlers and their Interceptors

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

(s/fdef handle-world-message!
  :args (s/cat :world-connection ::world-connection
               :global-bus ::bus/event-bus
               ;; Q: Right?
               :raw-message bytes?))
(defn handle-world-message!
  "Forward message from the browser to a world"
  [{:keys [::bus/event-bus
           ::lamport/clock
           ::world-stop-signal]
    :as world-connection}
   ;; This is really just for the sake of the stop
   ;; notification
   global-bus
   raw-message]
  (lamport/do-tick clock)
  (if (not= raw-message world-stop-signal)
    ;; Just forward this along
    (bus/publish! event-bus raw-message)
    (do
      ;; Tell the world that a browser connection has disconnected from.
      ;; it.
      ;; FIXME: Need to specify which connection.
      ;; It's tempting to use the session-id.
      ;; That temptation almost seems like a yellow, if not red, flag.
      ;; It seems like it would be safer to give each world its own
      ;; version of the session-id.
      ;; Then maintain a map of "real" session-ids to what the world
      ;; sees.
      ;; This way, one world doesn't have any way to identify its
      ;; sessions in other worlds.
      (bus/publish! event-bus :frereth/disconnect)
      ;; FIXME: This needs its own handler that can
      ;; update the session atom to mark the world
      ;; deactivated.
      ;; Which means that we need both the session-id
      ;; and world-key.
      (bus/publish! global-bus :frereth/disconnect-world
                    (select-keys world-connection
                                 [::connection/session-id
                                  :frereth/world-key])))))

(def world-forked
  {:name ::world-forked
   :enter (fn [{{:keys [::connection/session-id
                        :frereth/worlds]
                 :as session} :frereth/session
                lamport ::lamport/clock
                :keys [::bus/event-bus
                       :request]
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
              ;; Or however that's configured.
              (if-let [world (world/get-pending worlds world-key)]
                (let [{actual-pid :frereth/pid  ; TODO: Refactor/rename this to :frereth/world-key
                       actual-session :frereth/session-id
                       constructor-key :frereth/world-ctor
                       :as decrypted} (decode-cookie cookie)]
                  (if (and (= session-id actual-session)
                           (= world-key actual-pid))
                    (if-let [connector (registrar/lookup session-id constructor-key)]
                      (let [world-stop-signal (gensym "frereth.stop.")
                            ;; Q: Is there a better way to do this?
                            world-bus (ig/init-key ::bus/event-bus {})
                            client (connector world-stop-signal
                                              (partial handle-world-message!
                                                       {::bus/event-bus world-bus
                                                        ::world-stop-signal world-stop-signal}
                                                       event-bus))]
                        (bus/publish! event-bus
                                      :frereth/ack-forked
                                      (assoc (select-keys context
                                                          [::connection/session
                                                           ::lamport/clock])
                                             :frereth/world-key world-key
                                             ::client client)))
                      (let [error-message (str "Cookie for unregistered World"
                                               "\nSession ID: " session-id
                                               "\nConstructor Key: " constructor-key
                                               ;; FIXME: This should be a Component in the System instead
                                               "\nRegistry: " (with-out-str (pprint registrar/registry)))]
                        (println error-message)
                        context))
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
                      context)))
                (do
                  (println "Need to make world lookup simpler. Could not find world")
                  (pprint {::among params
                           ::world-key world-key
                           ::world-key-class (class world-key)})
                  context))))})

(def world-forking
  {:name ::world-forking
   :enter (fn [{:keys []
                lamport ::lamport/clock
                :as context}
               ;; FIXME: These parameters are totally wrong.
               ;; Should have a session in the context.
               sessions
               session-id
               {:keys [:frereth/command
                       ;; It seems like it would be better to make this explicitly a
                       ;; :frereth/world-key instead.
                       ;; TODO: Refactor it
                       :frereth/pid]
                :as body}]
            (throw (RuntimeException. "Needs the same treatment as forked"))
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
;;;; Public

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
  "Deserialize and dispatch a raw message from the UI portion"
  ;; This consumes messages from the web-socket associated
  ;; with session-id until that web-socket closes.
  ;; I could argue that pretty much all the implementation details above
  ;; this really belong in a shared frereth.app.renderer.lib ns.
  ;; This part's specific to the web-socket interface.
  ;; I don't think anything else is.
  [session-atom session-id clock  message-string]
  ;; STARTED: Break this up into a Pedestal interceptor chain
  (try
    ;; TODO: Build the terminators/interceptors/routers elsewhere.
    ;; It's very tempting to make the routes part of the specific
    ;; session.
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
      ;; Current theory:
      ;; Follow the front-end idea of "data down, events up."
      ;; Any side-effects that should happen get triggered by
      ;; posting messages to the event bus
      (let [{:keys [:response]
             :as context} (intc-chain/execute context)]
        (if response
          (println (str "Response for:\n" message-string
                        "\nWhat would make sense to happen with:\n"
                        (with-out-str (pprint response))))
          (println "No 'response' for:\n" message-string))))
    (catch Exception ex
      (println ex "trying to deserialize/dispatch" message-string))))

(defmethod ig/init-key ::internal
  [_ {:keys [::bus/event-bus]
      :as component}]
  (assoc component
         ::disconnected (connect-handler event-bus :frereth/disconnect-world handle-disconnected)
         ::forked (connect-handler event-bus :frereth/ack-forked handle-forked)))

(defmethod ig/halt-key! ::internal
  [_ {:keys [::disconnected
             ::forked]}]
  (doseq [stream [disconnected forked]]
    (strm/close! stream)))
