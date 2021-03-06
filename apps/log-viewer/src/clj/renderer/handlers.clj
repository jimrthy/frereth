(ns renderer.handlers
  ;; FIXME: A lot of this (esp. the on-message! chain) seems like it might
  ;; be more generally useful for other renderers.
  ;; Then again, any such hypothetical renderers will be built around a
  ;; totally different architecture, so probably not.
  "Handlers for the messages involving individual apps"
  (:require
   [backend.event-bus :as bus]
   [backend.specs :as backend-specs]
   [client.registrar :as registrar]
   [clojure.pprint :refer (pprint)]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.connection :as connection]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.world :as world]
   [frereth.cp.shared.util :as util]
   [frereth.weald.logging :as log]
   [frereth.weald.specs :as weald]
   [integrant.core :as ig]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :as intc]
   [io.pedestal.interceptor.chain :as intc-chain]
   [io.pedestal.http.route.definition.table :as table-route]
   [manifold.deferred :as dfrd]
   [manifold.stream :as strm]
   [renderer.sessions :as sessions]
   [clojure.set :as set])
  (:import java.util.Base64))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;; Low-level building blocks
(s/def ::cookie (s/keys :req [:frereth/world-key
                              :frereth/state
                              :frereth/world-ctor]))

;; Note that this assumes messages go to a specific world.
;; Q: How valid is that assumption?
(s/def ::message-envelope (s/keys :req [:frereth/action
                                        :frereth/body
                                        :frereth/lamport
                                        :frereth/world-key]))

;;; Higher-level concepts

(s/def ::common-connection (s/keys :req [::bus/event-bus
                                         ::lamport/clock
                                         ::weald/logger
                                         ::weald/state-atom
                                         :frereth/session-id]))

(s/def ::world-connection (s/merge ::common-connection
                                   (s/keys :req [:frereth/world-key
                                                 ::world-stop-signal])))

(s/def ::session-connection (s/merge ::common-connection
                                     (s/keys :req [::backend-specs/routes
                                                   ::sessions/session-atom])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Helpers

(s/fdef build-cookie
  :args (s/cat :session-id :frereth/session-id
               :world-key ::world-key
               :command :frereth/command)
  :ret bytes?)
(defn build-cookie
  "Based around the Cookie packet in CurveCP"
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
  ;; When the [browser] client notifies us that it has forked, we can
  ;; decrypt the cookie and mark this World active for the
  ;; appropriate SESSION.
  ;; Q: Would it be worthwhile to add another layer to this?
  ;; Have the browser query for the worker code. We use this
  ;; cookie to inject another short-term cookie key into that
  ;; worker code.
  ;; Then the ::forked handler could verify them all.
  ;; Update to that plan: really just want a ::joined handler
  ;; It seems like we really have to do something along those
  ;; lines, since we cannot possibly trust the browser, and the
  ;; HTTP request could come from anywhere.
  ;; A malicious client could still share the client's private
  ;; key and request a billion copies of the browser page.
  ;; Then again, that seems like a weakness in CurveCP also.
  ;; That *is* the hard part: which keys do you trust?
  (let [dscr {:frereth/world-key world-key
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
  (let [stream (bus/do-subscribe event-bus signal)]
    (strm/consume handler stream)
    stream))

(s/fdef do-wrap-message
  :args (s/or :with-body (s/cat :log-state-atom ::weald/state-atom
                                :lamport ::lamport/clock
                                :world-key :frereth/world-key
                                :action :frereth/action
                                :value :frereth/body)
              :sans-body (s/cat :log-state-atom ::weald/state-atom
                                :lamport ::lamport/clock
                                :world-key :frereth/world-key
                                :action :frereth/action))
  :ret ::message-envelope)
;; TODO: Just use weald's clock instead.
;; Alt: modify weald to define something like a clock
;; protocol that allows callers to override how it works.
(defn do-wrap-message
  "Combine raw parameters into a standard map for serialization"
  ([log-state-atom lamport world-key action]
   (swap! log-state-atom
          #(log/trace %
                      ::do-wrap-message
                      "Building"
                      {:frereth/action action}))
   (swap! lamport inc)
   {:frereth/action action
    :frereth/lamport @lamport
    :frereth/world-key world-key})
  ([log-state-atom lamport world-key action value]
   (let [result
         (assoc (do-wrap-message log-state-atom lamport world-key action)
                :frereth/body value)]
     (swap! log-state-atom
            #(log/trace %
                        ::do-wrap-message
                        "Extending with value"
                        {:frereth/body value}))
     result)))

(s/fdef post-real-message!
  :args (s/cat :log-state-atom ::weald/state-atom
               :session ::sessions/session
               :world-key :frereth/world-key
               ;; This needs to be anything that we can cleanly
               ;; serialize
               :wrapper any?)
  :ret any?)
(defn post-real-message!
  ;; TODO: Move this elsewhere.
  ;; This really needs to be something that's dependency-injected
  ;; so callers can manage the details.
  ;; That's mostly coming from the standpoint of being able to test
  ;; sensibly.
  ;; OTOH, it shouldn't get called the way it does.
  ;; TODO: Handle the call view a :leave interceptor that performs
  ;; the side-effects.
  "Forward serialized value from browser to World"
  [log-state-atom
   {:keys [:frereth/session-id
           ::connection/state
           ::connection/web-socket]
    :as session}
   world-key
   wrapper]
  (swap! log-state-atom
         #(log/trace %
                     ::post-real-message!
                     "Trying to send"
                     {::wrapper wrapper
                      :frereth/world-key world-key
                      :frereth/session-id session-id}))
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
            (dfrd/on-realized success
                              (fn [succeeded]
                                (swap! log-state-atom
                                       #(log/debug %
                                                   ::post-real-message!
                                                   "Forwarded"
                                                   {::wrapper wrapper
                                                    ::success succeeded})))
                              (fn [failure]
                                (swap! log-state-atom
                                       #(log/error %
                                                   ::post-real-message!
                                                   "Forwarding failed"
                                                   {::wrapper wrapper
                                                    ::failure failure})))))
          (catch Exception ex
            (swap! log-state-atom
                   #(log/exception % ex
                                   ::post-real-message!
                                   "Message forwarding failed")))))
      (catch Exception ex
        (swap! log-state-atom
               #(log/exception % ex ::post-real-message!
                               "Serializing message failed"))))
    (do
      (swap! log-state-atom
             #(log/error % ::post-real-message! "Session not active"))
      (throw (ex-info "Trying to POST to inactive Session"
                      {::connection/session session
                       :frereth/world-key world-key})))))

;;; I'm not convinced this belongs in here either.
;;; It only makes sense for a web-based renderer.
;;; Then again, I'm building a prototype. Not the actual framework.
(s/fdef post-message!
  :args (s/or :with-value (s/cat :log-state-atom ::weald/state-atom
                                 :session :frereth/session
                                 :lamport ::lamport/clock
                                 :world-key :frereth/world-key
                                 :action :frereth/action
                                 :value :frereth/body)
              :sans-value (s/cat :log-state-atom ::weald/state-atom
                                 :session :frereth/session
                                 :lamport ::lamport/clock
                                 :world-key :frereth/world-key
                                 :action :frereth/action))
  :ret any?)
(defn post-message!
  "Forward message from browser to World"
  ([log-state-atom session lamport world-key action value]
   (swap! log-state-atom
          #(log/trace %
                      ::post-message! "with value" value))
   (let [wrapper (do-wrap-message log-state-atom lamport world-key action value)]
     (post-real-message! log-state-atom session world-key wrapper)))
  ([log-state-atom session lamport world-key action]
   (swap! log-state-atom
          #(log/trace %
                      ::post-message! "without value"))
   (let [wrapper (do-wrap-message log-state-atom lamport world-key action)]
     (post-real-message! session world-key wrapper))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Privileged Handlers

(defmacro defhandler
  "Define a privileged handler for a specific message

  Has access to several magic anaphoric-ish parameters:
  * event-bus
  * clock
  * session-atom
  * session
  * world-key
  * world-stop-signal
  * log-state-atom"
  [handler-name
   ;; It's really tempting to try to combine message-spec
   ;; and message-arg.
   ;; After all, message-arg will pretty much always be the
   ;; list of required keys...right?
   message-spec
   message-arg
   & handler-body]
  (let [real-handler-name handler-name]
    `(do
       (s/fdef ~real-handler-name
         :args (s/cat :this ::session-connection
                      :session-id :frereth/session-id
                      :message ~message-spec)
         :ret any?)
       (defn ~real-handler-name
         [~'{:keys [::bus/event-bus
                    ::lamport/clock
                    ::sessions/session-atom
                    :frereth/world-key
                    ::world-stop-signal]
             log-state-atom ::weald/state-atom
             :as this}
          ~'session-id
          ~(first message-arg)]
         (println "Top of some handler")
         (let [log-label# (keyword (str *ns*) (str ~real-handler-name))]
           (try
             (swap! ~'log-state-atom #(log/trace %
                                                 log-label#
                                                 "Incoming"))
             (let [~'session (-> ~'session-atom
                                 deref
                                 (get ~'session-id))]
               ~@handler-body)
             (catch Exception ex#
               (println "Oops in def'd handler:" ex#)
               (swap! ~'log-state-atom #(log/exception %
                                                       ex#
                                                       log-label#))
               ;; Q: What should this return?
               (throw ex#))
             (finally
               (let [logger# (::weald/logger ~'this)]
                 (swap! ~'log-state-atom #(log/flush-logs! logger# %))))))))))

(defhandler handle-disconnected
  (s/keys :req [:frereth/session-id :frereth/world-key])
  [{supplied-session-id :frereth/session-id
    supplied-world-key :frereth/world-key
    :as real-params}]
  (if (and (= session-id supplied-session-id)
           (= world-key supplied-world-key))
    (do
      (swap! log-state-atom
             #(log/debug %
                         ::handle-disconnected
                         "Disconnecting"
                         {:frereth/session-id session-id
                          :frereth/world-key world-key}))
      (swap! session-atom
             #(sessions/deactivate-world % session-id world-key)))
    (swap! log-state-atom
           #(log/warn %
                      ::handle-disconnected
                      "Disconnection mismatch"
                      {:supplied/session-id supplied-session-id
                       :expected/session-id session-id
                       :supplied/world-key supplied-world-key
                       :expected/world-key world-key}))))

(defhandler handle-forked
  (s/keys :req [::client
                :frereth/session-id
                :frereth/world-key])
  [{:keys [::client
           :frereth/session-id
           :frereth/world-key]}]
  ;; This is really handle-joined.
  ;; The forking part signalled that the browser wanted a World to
  ;; start.
  ;; Assuming the client is authorized, we should start it.
  ;; The part that downloads the code to visualize and interact is
  ;; a totally different animal.
  (post-message! log-state-atom
                 session
                 clock
                 world-key
                 :frereth/ack-forked)
  (sessions/activate-forking-world session-atom
                                   session-id
                                   world-key
                                   client))

(defhandler handle-forking
  any?
  [{:keys [::cookie
           :frereth/world-key]
    :as message}]
  (try
    (swap! log-state-atom #(log/debug %
                                      ::handle-forking
                                      "top"
                                      {::message message
                                       ::session-connection this
                                       ::cookie cookie}))
    ;; It's tempting to add the world to the session here.
    ;; Actually, there doesn't seem like any good reason
    ;; not to (if not earlier).
    ;; Except that there are lots of places for things to break along
    ;; the way.
    ;; There isn't any reason to waste local state on a world that could
    ;; easily be orphaned if anything goes wrong.
    ;; But this is an important starting point.
    ;; TODO: The thinking behind this decision was wrong.
    ;; If the Principal that made the request to Fork is authorized,
    ;; then start it.
    ;; The round trip that follows immediately to download source and
    ;; start a Web Worker is something distinct.
    ;; That part is really a Join.

    ;; On a different note:
    ;; This is working at too high a layer in the abstraction tree.
    ;; We should really be running something like update-in
    ;; with just the piece that's being changed here (in
    ;; this case, the appropriate Session).
    ;; Think of something like Om cursors or Om.next queries (?).
    ;; Those don't seem as useful in a game as they are in a basically
    ;; static SPA, but the idea does apply.
    ;; FIXME: Messages to post! should be part of the return value.
    ;; Q: Should they really?
    (post-message! log-state-atom
                   session
                   clock
                   world-key
                   :frereth/ack-forking
                   {:frereth/cookie cookie})
    (catch Exception ex
      (swap! log-state-atom #(log/exception %
                                            ex
                                            ::handle-forking
                                            "Trying to post :frereth/ack-forking"
                                            {:frereth/session-id session-id
                                             :frereth/world-key world-key})))))

(s/fdef log-edges
  :args (s/cat :location keyword?  ; :frereth/atom better?
               :phase #{:enter :leave})
  :ret ::backend-specs/context)
(defn log-edges
  [location
   phase
   {:keys [::weald/logger]
    log-state-atom ::weald/state-atom
    :as context}]
  (let [log-state (log/trace @log-state-atom
                             location
                             phase
                             (-> context
                                 (dissoc ::weald/logger
                                         ::weald/state-atom)
                                 (assoc ::queue-length (-> context
                                                           :intc-chain/queue
                                                           count))))]
    (reset! log-state-atom
            (if (and (= :leave phase)
                     (= ::outer location))
              ;; Want to flush when we leave the outer logger
              ;; It would be premature optimization, but is seems like
              ;; it might be worth setting this up as its own special-
              ;; case function to avoid the branching.
              ;; A lot of that depends on how partial works with the
              ;; JIT.
              (log/flush-logs! logger
                               log-state)
              log-state)))
  context)

(defn log-error
  [where
   {:keys [::weald/logger]
    log-state-atom ::weald/state-atom
    :as context}
   ex]
  (swap! log-state-atom #(log/flush-logs! logger
                                          (log/exception %
                                                         ex
                                                         where
                                                         ""
                                                         (dissoc context
                                                                 ::weald/logger
                                                                 ::weald/state-atom))))
  context)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Outer Handlers and their Interceptors

(def inner-logger
  "Logs the context just before/after the router"
  {:name ::inner-logger
   :enter (partial log-edges ::inner :enter)
   :leave (partial log-edges ::inner :leave)
   :error (partial log-error ::inner)})

(def outer-logger
  "Logs context around all other interceptors"
  {:name ::outer-logger
   :enter (partial log-edges ::outer :enter)
   :leave (partial log-edges ::outer :leave)
   :error (partial log-error ::outer)})

(def request-deserializer
  "Convert raw message string into a clojure map"
  {:name ::request-deserializer
   :enter (fn [{log-state-atom ::weald/state-atom
                :keys [:request]
                :as context}]
            (let [{deserialized-request :request
                   :as result} (serial/deserialize request)]
              (swap! log-state-atom #(log/trace %
                                                ::request-deserializer
                                                "Handling"
                                                {::initial-request request
                                                 ::body-type (type request)
                                                 ::result-request deserialized-request
                                                 ::deserialized result}))
              (into context result)))})

(s/fdef build-ticker
  :args (s/cat :clock ::lamport/clock)
  :ret ::backend-specs/interceptor)
(defn build-ticker
  "Update the Lamport clock"
  [clock]
  {:name ::lamport-ticker
   :enter (fn [{:keys [request]
                remote-clock :frereth/lamport
                log-state-atom ::weald/state-atom
                :as context}]
            ;; Q: Do I want to handle it this way?
            ;; (it associates the current clock-tick, rather
            ;; than an actual clock)
            (swap! log-state-atom #(log/trace %
                                              ::ticker
                                              "Synchronizing"))
            ;; TODO: Would be really nice to also coordinate
            ;; with the logger's Lamport clock
            (lamport/do-tick (::lamport/clock context)
                             remote-clock)
            ;; There are 2 pieces of mutable state used here:
            ;; the local Lamport clock and the log-state-atom.
            ;; They're convenient, but seem like a terrible
            ;; idea.
            context)})

(def session-extractor
  "Pull the current session out of the session atom"
  {:name ::session-active?
   :enter (fn [{:keys [::lamport/clock
                       ::weald/logger
                       ::sessions/session-atom
                       :frereth/session-id]
                log-state-atom ::weald/state-atom
                :as context}]
            ;; It shouldn't be possible to get here if the session isn't
            ;; active.
            ;; It seems worth checking for that scenario out of paranoia.
            (if-let [session (sessions/get-active-session @session-atom session-id)]
              (do
                (swap! log-state-atom
                       #(log/debug %
                                   ::session-extractor
                                   "Found an active session"
                                   {:frereth/session-id session-id
                                    :frereth/session session}))
                (-> context
                    ;; Interceptors that need the session-id can get it
                    ;; from the session
                    (dissoc ::sessions/session-atom :frereth/session-id)
                    (assoc :frereth/session session)))
              (do
                (swap! log-state-atom
                       #(log/error %
                                   ::session-extractor
                                   "No matching active session to extract"
                                   context))
                (lamport/do-tick clock)
                (throw (ex-info "Websocket message for inactive session. This should be impossible"
                                (assoc context
                                       ::sessions/active (keys (sessions/get-by-state @session-atom
                                                                                      ::sessions/active))
                                       ::sessions/pending (sessions/get-by-state @session-atom
                                                                                 ::sessions/pending)))))))})

(s/fdef decode-cookie
  :args (s/cat :log-state-atom ::weald/state-atom
               :cookie-bytes bytes?)
  :ret ::cookie)
(defn decode-cookie
  [log-state-atom cookie-bytes]
  (swap! log-state-atom #(log/trace %
                                    ::decode-cookie
                                    "Trying to get the cookie bytes"
                                    {::cookie-bytes cookie-bytes
                                     ::cookie-byte-type (type cookie-bytes)}))
  ;; TODO: Need a pair of functions for round-tripping the Cookie
  ;; through encryption.
  ;; They need to be stateful enough to cope with a minute-key.
  (let [plain-cookie (.decode (Base64/getDecoder) cookie-bytes)
        cookie-string (String. plain-cookie)]
    (swap! log-state-atom #(log/trace %
                                      ::decode-cookie
                                      "Trying to decode"
                                      {::cookie-string cookie-string
                                       ::cookie-string-type (type cookie-string)
                                       ::plain-cookie plain-cookie
                                       ::plain-cookie-type (type plain-cookie)}))
    (serial/deserialize cookie-string)))

(s/fdef handle-world-message!
  :args (s/cat :world-connection ::world-connection
               :global-bus ::bus/event-bus
               ;; It's tempting to use a ByteBuf here.
               ;; That doesn't seem like an option, if everything else
               ;; uses raw bytes.
               ;; OTOH, this leads to the possibility that a misbehaving
               ;; World can just consume memory and take down the entire
               ;; system.
               ;; The answer there is to be careful about which worlds
               ;; you Serve, but...it seems like there really should be
               ;; a way to isolate world memory.
               ;; In a lot of ways, each one needs its own limited heap
               ;; and stack space.
               ;; That gets into details about java memory management
               ;; that I've never explored, but need to.
               ;;
               ;; Follow-up note to that original:
               ;; this isn't even about Worlds that are running on a
               ;; local Server.
               ;; This really represents the web-server-side state of
               ;; a World to which your Renderer has connected via the
               ;; Client library.
               ;; This potentially gets much nastier if multiple end-
               ;; users are connecting to this at the same time to
               ;; interact with multiple worlds.
               ;; Ultimately, this really does need to be something like
               ;; a ByteBuf. Each connection gets a pool of these, and
               ;; it has the responsibility to release them as fast as
               ;; possible.
               ;; That still doesn't protect against a misbehaving
               ;; World, so maybe we're back into the "this is all a
               ;; terrible idea" realm.
               :raw-message bytes?))
(defn handle-world-message!
  "Forward message from the browser to a world"
  [{:keys [::bus/event-bus
           ::lamport/clock
           ::world-stop-signal]
    log-state-atom ::weald/state-atom
    :as world-connection}
   raw-message]
  (lamport/do-tick clock)
  (if (not= raw-message world-stop-signal)
    ;; Just forward this along
    (bus/publish! log-state-atom event-bus ::topic? raw-message)
    (do
      ;; Tell the world that a browser connection has disconnected from
      ;; it.
      ;; TODO: Ditch the event-bus.
      ;; I already worked this out with frereth-cp.
      ;; Handle this with straight function calls. That's plenty decoupled for
      ;; an inproc interface.
      ;; (That function could trigger a broadcast, fanout, or whatever other
      ;; communication mechanism actually makes sense, but it's more loosely
      ;; coupled for the caller to not know).
      (bus/publish! log-state-atom event-bus :frereth/disconnect ::what-goes-here?)
      (bus/publish! log-state-atom
                    event-bus
                    :frereth/disconnect-world
                    (select-keys world-connection
                                 [:frereth/session-id
                                  :frereth/world-key])))))

(def world-forked
  "This is really more like world-joined.

  We can totally fork a new World without having any client Join.

  That's pretty much exactly what a daemon is all about."
  {:name ::world-forked
   :enter (fn [{{:keys [::connection/session-id
                        :frereth/worlds]
                 :as session} :frereth/session
                lamport ::lamport/clock
                :keys [::bus/event-bus
                       :request]
                log-state-atom ::weald/state-atom
                :as context}]
            (let [{:keys [:params]} request
                  {world-key :frereth/world-key
                   :keys [:frereth/cookie]} params]
              ;; Once the browser has its worker up and running, we need to start
              ;; interacting.
              ;; Actually, there's an entire lifecycle here.
              ;; It's probably worth contemplating the way React handles the
              ;; idea (though it may not fit at all).
              ;; Main point:
              ;; This needs to start up a new System of Component(s) that
              ;; :frereth/forking prepped.
              ;; Well, not exactly.
              ;; Think of a client connecting to the MySQL daemon.
              ;; Or a tmux session.
              ;; Joining a game server (which is what this really means) *is*
              ;; likely to trigger a chain of events, but they mostly shouldn't
              ;; happen at this level.
              (if-let [world (world/get-pending worlds world-key)]
                (let [{actual-pid :frereth/world-key
                       actual-session :frereth/session-id
                       constructor-key :frereth/world-ctor
                       :as decrypted} (decode-cookie log-state-atom cookie)]
                  (if (and (= session-id actual-session)
                           (= world-key actual-pid))
                    (if-let [connector (registrar/lookup session-id constructor-key)]
                      (let [world-stop-signal (gensym "frereth.stop.")
                            ;; Q: Is there a better way to do this?
                            ;; A: Yes. It ties right in with eliminating the
                            ;; event-bus dependency at this level.
                            world-bus (ig/init-key ::bus/event-bus {})
                            client (connector world-stop-signal
                                              (partial handle-world-message!
                                                       {::bus/event-bus world-bus
                                                        ::world-stop-signal world-stop-signal}
                                                       event-bus))]
                        (bus/publish! log-state-atom
                                      event-bus
                                      :frereth/ack-forked
                                      ;; Q: How many of these parameters are redundant?
                                      (assoc (select-keys context
                                                          [::connection/session
                                                           ::lamport/clock])
                                             :frereth/world-key world-key
                                             ::client client)))
                      (do
                        (swap! log-state-atom
                               #(log/error %
                                           ::world-forked
                                           "Cookie for unregistered World"
                                           {:frereth/session-id session-id
                                            ::constructor-key constructor-key
                                            ::registrar/registry registrar/registry}))
                        context))
                    ;; This could be a misbehaving World.
                    ;; Or it could be a nasty bug in the rendering code.
                    ;; Well, it would have to be a nasty bug for a World to misbehave
                    ;; this way.
                    ;; At this point, we should probably assume that the browser is
                    ;; completely compromised and take drastic measures.
                    ;; Notifying the user and closing the websocket to end the session
                    ;; might be a reasonable start.
                    ;; Q: How do we notify the user if the browser session is
                    ;; compromised?
                    ;; A: Well, do our best. We need a message hook to signal things
                    ;; like an email to the sysadmin and a siren for the devops team
                    (let [error-message
                          (str "session/world mismatch:\n* "
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
                      (swap! log-state-atom #(log/error %
                                                        ::world-forked
                                                        error-message))
                      context)))
                (do
                  (swap! log-state-atom
                         #(log/error %
                                     ::world-forked
                                     "Could not find world. Should probably make world lookup simpler."
                                     {::among params
                                      ::world-key world-key
                                      ::world-key-class (class world-key)}))
                  context))))})

(def world-forking
  ;; The browser has established an authenticated session (TBD) and a
  ;; websocket.
  ;; Now it's used the websocket to send us a notification that it wants
  ;; to spawn/fork a new connection to some world.
  ;; We need set up that connection and the event loop that passes
  ;; messages between the world and the browser.
  "Something on browser has requested a new World instance"
  {:name ::world-forking
   :enter (fn [{:keys [::bus/event-bus
                       :request]
                lamport ::lamport/clock
                log-state-atom ::weald/state-atom
                {:keys [:frereth/session-id]
                 :as session} :frereth/session
                :as context}]
            (swap! log-state-atom #(log/trace %
                                              ::world-forking
                                              "Top"
                                              {:frereth/session-id session-id}))
            (let [{{:keys [:frereth/command
                            ;; It seems like it would be better to make this explicitly a
                            ;; :frereth/world-key instead.
                            ;; TODO: Refactor it
                            :frereth/world-key]
                     :as body} :params} request]
              ;; TODO: Look into clojure core functionality.
              ;; Surely there's a better approach than these nested ifs
              ;; some-> would be obvious, if we didn't need error handling.
              ;; Since we do, there might not be a good alternative.
              (if (and command world-key)
                (if session
                  (if-not (connection/get-world session world-key)
                    ;; TODO: Need to check with the Client registry
                    ;; to verify that command is legal for the current
                    ;; session (at the very least, this means authorization)
                    (let [cookie (build-cookie session-id world-key command)]
                      (swap! log-state-atom #(log/trace %
                                                        ::world-forking
                                                        "Publishing cookie to event bus to trigger :frereth/ack-forking"
                                                        {::cookie cookie
                                                         ::bus/event-bus event-bus
                                                         :frereth/session-id session-id
                                                         :frereth/world-key world-key
                                                         :frereth/command command}))
                      ;; TODO: Return this topic/message pair instead.
                      ;; Let the caller handle side-effects like the publish! separately.
                      (bus/publish! log-state-atom
                                    event-bus
                                    :frereth/ack-forking
                                    {::cookie cookie
                                     :frereth/world-key world-key})
                      (assoc context :response ::notified))
                    (do
                      (swap! log-state-atom #(log/warn %
                                                        ::world-forking
                                                        "Error: trying to re-fork world"
                                                        {:frereth/world-key world-key
                                                         :frereth/session session}))
                      context))
                  (do
                    (swap! log-state-atom #(log/warn %
                                                     ::world-forking
                                                     "Missing session"
                                                     context))
                    context))
                (do
                  (swap! log-state-atom #(log/warn %
                                                   ::world-forking
                                                   "Missing either/both"
                                                   {:frereth/world-key world-key
                                                    :frereth/command command
                                                    :request request
                                                    ::request-keys (keys request)}))
                  context))))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef build-routes
  :args nil
  :ret ::backend-specs/routes)
(defn build-routes
  []
  #{["/api/v1/forked" :post world-forked :route-name ::forked]
    ["/api/v1/forking" :post world-forking :route-name ::forking]})

(s/fdef on-message!
  :args (s/cat :session-connection ::session-connection
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
  ;; In retrospect, this seems like a lot of hoops to jump through
  ;; just to send a message to an event bus.
  ;; If there are really just a small, well-defined subset of
  ;; control messages going back and forth, using interceptor chains
  ;; like this is complete overkill.
  [{:keys [::bus/event-bus
           ::weald/logger
           ::backend-specs/routes
           ::sessions/session-atom
           :frereth/session-id]
    log-state-atom ::weald/state-atom
    :as session-connection}
   raw-interceptors
   message-string]
  (swap! log-state-atom
         #(log/info %
                    ::on-message!
                    "Incoming"
                    {::body message-string
                     :frereth/session-id session-id}))
  ;; STARTED: Break this up into a Pedestal interceptor chain
  (try
    ;; TODO: Build the terminators/interceptors/routers elsewhere.
    ;; It's very tempting to make the routes part of the specific
    ;; session.
    ;; Definitely need to pull this all apart so I can unit test what I have.
    (let [terminators #{}  ; Q: Is there anything useful to put in here?
          custom-verbs #{:frereth/forward}  ; Q: Is a single extra verb worth the effort?
          ;; TODO: need an option, at least in debug mode, for this
          ;; to be a function that gets called each time.
          routes (if (set? routes)
                   routes
                   ;; Q: Doesn't Pedestal handle the "build new set of routes
                   ;; each time" for us? Or did I bypass that?
                   (routes))
          ;; With this approach, need to combine custom-verbs with the
          ;; standard HTTP ones.
          ;; It's very tempting to make this the responsibility of the
          ;; route-building function.
          ;; That temptation is wrong.
          ;; Want the code that builds the routes to be as simple,
          ;; clean, and declarative as possible.
          ;; OTOH, this misses a clean separation: where do the
          ;; custom-verbs come from?
          ;; It's tempting to just process the routes and assume that
          ;; anything listed there is legal.
          ;; I'm curious why Pedestal didn't just take that approach.
          verbs (set/union @#'table-route/default-verbs
                           custom-verbs)
          processed-routes (try (table-route/table-routes {:verbs verbs}
                                                          routes)
                                (catch Throwable ex
                                  ;; table-routes may very well throw an
                                  ;; assert if something goes wrong.
                                  ;; That will escape into the bowels
                                  ;; of manifold without something like
                                  ;; this.
                                  (throw (ex-info "Expanding Routes failed"
                                                  {::verbs verbs
                                                   ::route-table routes}
                                                  ex))))
          raw-interceptors (conj raw-interceptors
                                 ;; It might make sense to parameterize the routing,
                                 ;; but it doesn't seem likely.
                                 ;; If I do add the concept of a URI path
                                 ;; here, I don't see adding in path params.
                                 ;; This dispatch needs to happen fast.
                                 ;; TODO: Inject another logger here.
                                 ;; :enter should log the :request (esp: what's
                                 ;; needed for query-params) while :leave
                                 ;; needs a way to verify that it was routed
                                 ;; somewhere. (Easiest approach: add a
                                 ;; response that's really just a signal that
                                 ;; it got handled. Possibly better: have the
                                 ;; response be a seq of functions to call
                                 ;; to trigger side-effects, like posting
                                 ;; messages to the event bus)
                                 (route/router processed-routes :map-tree))
          ;; This isn't an HTTP request handler, so the details are
          ;; a little different.
          ;; Especially: use the :action as the request's :verb
          context-map (assoc (select-keys session-connection
                                          [::lamport/clock
                                           ::bus/event-bus
                                           ::weald/logger
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
                                           ::sessions/session-atom
                                           :frereth/session-id
                                           ::weald/state-atom])

                             :request message-string
                             ::intc-chain/terminators terminators)
          context (intc-chain/enqueue context-map
                                      (map intc/interceptor
                                           raw-interceptors))
          _ (swap! log-state-atom
                   #(log/info %
                              ::on-message!
                              "Ready to trigger interceptor-chain"))
          ;; The actual point.
          ;; It's easy to miss this in the middle of setting up the
          ;; chain handler.
          ;; Which is one reason this is worth keeping separate from the
          ;; dispatching code, which needs to move elsewhere.
          ;; Current approach:
          ;; Follow the front-end idea of "data down, events up."
          ;; Any side-effects that should happen get triggered by
          ;; posting messages to the event bus
          {:keys [:response]
           :as context} (intc-chain/execute context)
          result-message
          (if response
            (str "Response for:\n" message-string
                 "\nWhat would make sense to happen?\n"
                 (with-out-str (pprint response)))
            (str "No 'response' for:\n" message-string))]
      (swap! log-state-atom
             #(log/debug %
                         ::on-message!
                         result-message
                         context)))
    (catch Exception ex
      (swap! log-state-atom
             #(log/exception %
                             ex
                             ::on-mesage!
                             "trying to deserialize/dispatch"
                             {::body message-string})))
    (finally
      (swap! log-state-atom
             #(log/flush-logs! logger %)))))

(let [handlers {::disconnected [:frereth/disconnect-world
                                handle-disconnected]
                ::forked [:frereth/ack-forked handle-forked]
                ::forking [:frereth/ack-forking handle-forking]}]
  (s/fdef do-connect
    :args (s/cat :component ::session-connection
                 :session-id :frereth/session-id)
    :ret ::session-connection)
  (defn do-connect
    ;; This has almost the same problem as my original Component approach.
    ;; It isn't quite as bad. At least I can call this from somewhere that
    ;; has a session-id.
    ;; Like when a Session connects.
    ;; That would allow each Session to have its own event-bus.
    ;; So there's a little isolation there.
    ;; But we don't have any isolation between the Worlds.
    ;; Then again...that part wouldn't make any sense at this level.
    ;; This really is managing the lifecycle of a world in a
    ;; session-connection.
    ;; Which means this module needs to be moved/renamed.
    ;; Bigger picture, though, this has to update the session-atom.
    ;; Logically, this is handling side-effects that are triggered by
    ;; messages that arrived at a system boundary.
    ;; I want to be paranoid about what's allowed to happen here
    ;; because it just seems dangerous.
    ;; These messages should be safe enough. They come from code
    ;; that I supplied (Q: right?) that couldn't possibly have been
    ;; compromised (also Q: right?)
    ;; There should never be a malicious actor on the other side
    ;; that manages to get the signing key to send disconnect
    ;; messages.
    ;; Or messages to trigger calls that are equivalent to
    ;; "format c:".
    ;; This still needs more thought.
    [{:keys [::bus/event-bus
             ::sessions/session-atom]
      log-state-atom ::weald/state-atom
      :as component}
     session-id]
    (swap! log-state-atom #(log/debug %
                                      ::do-connect
                                      "Setting up world handlers for session"
                                      {::sessions/session-atom session-atom}))
    (swap! session-atom
           (fn [sessions]
             (update sessions session-id
                     (fn [session]
                       (assoc
                        (reduce (fn [acc [tag [event-id handler]]]
                                  (swap! log-state-atom #(log/trace %
                                                                    ::do-connect
                                                                    "Connecting handler"
                                                                    {::handler handler
                                                                     ::signal event-id
                                                                     ::bus/event-bus event-bus}))
                                  (assoc acc tag (connect-handler event-bus
                                                                  event-id
                                                                  (partial handler
                                                                           component
                                                                           session-id))))
                                session
                                handlers)
                        ::bus/event-bus event-bus)))))
    component)

  (defn disconnect!
    [component session-id]
    (swap! (::sessions/session-atom component)
           (fn [sessions]
             (update sessions session-id
                     (fn [session]
                       (reduce (fn [acc tag]
                                 (strm/close! (tag session))
                                 (dissoc session tag))
                               session
                               (keys handlers))))))))
