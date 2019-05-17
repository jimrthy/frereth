(ns backend.system
  "Layers that define the backend portions.

  These include the web/renderer, client, and server."
  (:require [aleph.udp :as udp]
            [backend.event-bus :as bus]
            [backend.web.routes :as routes]
            [backend.web.service]
            [client
             [networking :as client-net]
             [propagate :as propagate]]
            [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [frereth.weald.logging :as log]
            [frereth.weald.specs :as weald]
            [integrant.core :as ig]
            [frereth.cp.message.specs :as msg-specs]
            [frereth.cp.client :as client]
            [frereth.cp.client.state :as client-state]
            [frereth.cp.shared :as shared]
            [frereth.cp.shared
             [crypto :as crypto]
             [specs :as shared-specs]]
            [frereth.apps.shared.lamport :as lamport]
            [renderer.handlers :as handlers]
            [renderer.sessions :as sessions]
            [server.networking]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Magic Constants

(def server-extension-vector (range 16))
(def server-name "log-viewer.test.frereth.com")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::client-connection-opts (s/keys :opt [::msg-specs/message-loop-name
                                              ::shared/long-pair
                                              ::client-net/socket
                                              ::shared-specs/srvr-name
                                              ::shared-specs/public-long
                                              ::client-state/server-extension
                                              ::client-state/server-addresses
                                              ::shared-specs/port
                                              ::weald/state]
                                        :req [::weald/logger]))
(s/def ::socket-opts any?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defmethod ig/init-key ::log-chan
  [_ _]
  ;; FIXME: Allow options to override details like
  ;; buffering.
  {::ch (async/chan)})

(defmethod ig/halt-key! ::log-chan
  [_ {:keys [::ch]
      :as config}]
  (async/close! ch)
  (assoc config ::ch nil))

(defmethod ig/init-key ::weald/logger
  [_ {:keys [::chan
             ::weald/logger]
      :as opts}]
  ;; This should get configured in waves, at least for interactive
  ;; development.
  ;; So allow the option to just supply an existing logger and make this
  ;; idempotent.
  (if logger
    logger
    (do
      ;; The only real reason to use a CompositeLog here is
      ;; to test it out.
      ;; Then again, debugging what the browser displays isn't a terrible
      ;; idea.
      ;; Writing to a file would probably be better for debugging than
      ;; STDOUT.
      (println "Calling async-log-factory for" (::ch chan))
      (let [std-out-logger (log/std-out-log-factory)
            async-logger
            (try
              (let [async-logger (log/async-log-factory (::ch chan))]
                (println "Creating the async logger succeeded")
                async-logger)
              (catch Exception ex
                (println ex "Creating the async-logger failed")
                nil))]
        (if async-logger
          (log/composite-log-factory [async-logger
                                      std-out-logger])
          std-out-logger)))))
;; It's tempting to add a corresponding halt! handler for ::logger,
;; to call flush! a final time.
;; But we don't actually have a log-state here.
;; Q: Change that?
;; And decide which is the chicken vs. the egg.

(defmethod ig/init-key ::weald/state-atom
  [_ {:keys [::weald/context]}]
  (atom (log/init context)))

;; FIXME: Move this into its own ns.
;; server.networking needs to reference the key, and I'd
;; like to make the dependency explicit.
(defmethod ig/init-key ::server-socket
  [_ {:keys [::server-port]
      ;; Note that the server-port actually needs to be shared
      :or {server-port 31425}
      :as opts}]
  (println "Starting server socket on port" server-port "(this may take a bit)")
  (let [result
        (assoc opts ::udp-socket @(udp/socket {:port server-port}))]
    (println "UDP socket ready to serve")
    result))

(defmethod ig/halt-key! ::server-socket
  [_ {:keys [::udp-socket]
      :as opts}]
  (when udp-socket
    (.close udp-socket)))

(defn server-child-handler
  "Doesn't belong in here. But it's a start"
  [incoming]
  (println (str "Server child received:" (vec incoming)
                ", a " (class incoming))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef client-ctor
  :args (s/cat :opts (s/keys :req [::shared-specs/public-long
                                   ::shared-specs/port
                                   ::weald/logger]
                             :opt [::connection-opts
                                   ::socket-opts
                                   ::weald/context]))
  :ret (s/keys :req [::client-net/connection
                     ::client-net/socket]))
(defn client-ctor
  [{:keys [::weald/logger
           ::connection-opts]
    log-context ::weald/context
    socket-opts ::socket-opts
    server-pk ::shared-specs/public-long
    server-port ::shared-specs/port
    :as opts}]
  {:pre [logger
         server-pk]}
  ;; FIXME: Connecting a new World Renderer needs to trigger this.
  ;; More interestingly, I want a button on the browser that triggers a restart.
  {::client-net/connection (into #:client.networking{::msg-specs/message-loop-name (str (gensym "Client-"))
                                                     ;; TODO: Server really should be doing auth based
                                                     ;; around the public key. Using something random
                                                     ;; is just a getting-started shortcut
                                                     ::shared/long-pair (crypto/random-key-pair)
                                                     ::client-net/socket (ig/ref ::client-net/socket)
                                                     ::shared-specs/srvr-name server-name
                                                     ::shared-specs/public-long server-pk
                                                     ::client-state/server-extension server-extension-vector
                                                     ::client-state/server-addresses [[127 0 0 1]]
                                                     ::shared-specs/port server-port
                                                     ::weald/logger logger
                                                     ::weald/state-atom (assoc {::weald/context ::client-connection}
                                                                          log-context)}
                                        connection-opts)
   ::client-net/socket socket-opts})

(s/fdef server-ctor
  :args (s/cat :opts (s/keys :req [::weald/context
                                   ::weald/logger
                                   ::socket]))
  :ret (s/keys :req [:server.networking/server
                     ::server-socket]))
(defn server-ctor
  [{:keys [::weald/logger]
    log-context ::weald/context
    socket-opts ::socket
    :as opts}]
  (when-not logger
    (throw (ex-info "Missing logger among"
                    {::keys (keys opts)
                     ::opts opts})))
  {:server.networking/server (into {::msg-specs/->child server-child-handler
                                    :server.networking/extension-vector server-extension-vector
                                    ;; This seems problematic, but it's definitely
                                    ;; required
                                    ;; FIXME: Just store the logger instance.
                                    ::weald/logger (ig/ref ::weald/logger)
                                    ::weald/state (ig/ref ::weald/state-atom)
                                    ;; FIXME: Can this use a network state?
                                    :server.networking/my-name server-name
                                    :server.networking/socket (ig/ref ::server-socket)}
                                   (::server opts))
   ::server-socket (into {::server-port 34122}
                         socket-opts)
   ::weald/logger logger
   ::weald/state-atom (assoc {::weald/context ::local-server}
                             log-context)})

(defn monitoring-ctor
  [{:keys [::clock
           ::event-bus
           ::internal-handlers
           ::log-chan
           ::log-context
           ::logger
           ::monitor
           ::routes]
    :as opts}]
  (println "Defining the Monitoring portion of the System")
  ;; The web-server portion is a baseline that I want to just
  ;; run in general.
  ;; That's so I can monitor things like the startup/shutdown of the
  ;; CurveCP server. And, for that matter, so I do that startup/shutdown
  ;; without reconnecting the web socket.
  ;; This flies in the face of the fundamental principle that a System
  ;; should really be an atomic whole, but that's the basic reality of
  ;; what I'm building here.
  {::routes/handler-map (into {::lamport/clock (ig/ref ::lamport/clock)
                               ::bus/event-bus (ig/ref ::bus/event-bus)
                               ::sessions/session-atom (ig/ref ::sessions/session-atom)
                               ::weald/logger (ig/ref ::weald/logger)
                               ::weald/state-atom (ig/ref ::weald/state-atom)}
                              routes)
   :backend.web.service/web-service (into {::routes/handler-map (ig/ref ::routes/handler-map)}
                                          (::web-server opts))
   ::bus/event-bus event-bus
   ;; This Component doesn't make any sense.
   ;; FIXME: it needs to go away.
   ;; Q: How will the route handlers send messages to the event bus?
   ::handlers/internal (into {::bus/event-bus (ig/ref ::bus/event-bus)
                              ::lamport/clock (ig/ref ::lamport/clock)
                              ::sessions/session-atom (ig/ref ::sessions/session-atom)
                              ::weald/state-atom (ig/ref ::weald/state-atom)}
                             internal-handlers)
   ;; Surely both server and client need access to this.
   ;; The renderer/session manager definitely does.
   ;; TODO: Share it.
   ::lamport/clock clock
   ::log-chan log-chan
   ;; Note that this is really propagating the Server logs.
   ;; The Client logs are really quite different...though it probably
   ;; makes sense to also send those here, at least for an initial
   ;; implementation.
   ::propagate/monitor (into {::propagate/log-chan (ig/ref ::log-chan)}
                             monitor)
   ::sessions/session-atom (::sessions/session-atom opts)
   ;; Q: Can I get away with just storing a logger instance here?
   ;; And is there any real reason not to?
   ::weald/logger (into {::chan (ig/ref ::log-chan)}
                        logger)
   ::weald/state-atom (into {::weald/context ::global}
                             log-context)})

(defn ctor [opts]
  "Set up monitor/server/client all at once"
  (as-> opts component
    (monitoring-ctor component)
    (server-ctor (into opts component))
    ;; This doesn't seem all that realistic.
    ;; In reality, we don't want a Client adding encryption and network
    ;; hops to localhost.
    ;; But it's a start.
    (client-ctor (into opts component))))
