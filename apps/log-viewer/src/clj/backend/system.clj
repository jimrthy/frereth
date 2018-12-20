(ns backend.system
  (:require [aleph.udp :as udp]
            [backend.web.server]
            [client.propagate :as propagate]
            [clojure.core.async :as async]
            [server.networking]
            [frereth.weald
             [logging :as log]
             [specs :as weald]]
            [integrant.core :as ig]
            [frereth.cp.message.specs :as msg-specs]))

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
  [_ {:keys [::chan]
      :as opts}]
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
            nil))
        actual-logger
        (if async-logger
          (log/composite-log-factory [async-logger
                                      std-out-logger])
          std-out-logger)]
    (assoc opts
           ::weald/logger actual-logger)))
;; It's tempting to add a corresponding halt! handler for ::logger,
;; to call flush! a final time.
;; But we don't actually have a log-state here.
;; Q: Change that?
;; And decide which is the chicken vs. the egg.

;; FIXME: Move this into its own ns.
;; server.networking needs to reference the key, and I'd
;; like to make the dependency explicit.
(defmethod ig/init-key ::server-socket
  [_ {:keys [::server-port]
      ;; Note that the server-port actually needs to be shared
      :or {server-port 31425}
      :as opts}]
  (println "Starting server socket on port" server-port)
  (assoc opts ::udp-socket @(udp/socket {:port server-port} )))

(defmethod ig/halt-key! ::server-socket
  [_ {:keys [::udp-socket]
      :as opts}]
  (when udp-socket
    (.close udp-socket))
  (dissoc opts ::udp-socket))

(defn server-child-handler
  "Doesn't belong in here. But it's a start"
  [incoming]
  (println (str "Server child received:" (vec incoming)
                ", a " (class incoming))))

(defn client-ctor
  [opts]
  ;; FIXME: Connecting a new World Renderer needs to create a new
  ;; UDP socket along with its own CurveCP Client that tries to
  ;; connect to this.
  (throw (RuntimeException. "Write this")))

(defn server-ctor
  [{:keys [::logger]
    :as opts}]
  (println "")
  {:server.networking/server (into {::msg-specs/->child server-child-handler
                                    :server.networking/extension-vector (range 16)
                                    ::weald/logger (ig/ref ::weald/logger)
                                    :server.networking/my-name "log-viewer.test.frereth.com"
                                    :server.networking/port 32154
                                    :server.networking/socket (ig/ref ::server-socket)}
                                   (::server opts))
   ::server-socket (::socket opts)
   ::weald/logger logger})

(defn monitoring-ctor
  [opts]
  (println "Defining the Monitoring portion of the System")
  ;; The web-server portion is a baseline that I want to just
  ;; run in general.
  ;; That's so I can monitor things like the startup/shutdown of the
  ;; CurveCP server. And, for that matter, so I do that startup/shutdown
  ;; without reconnecting the web socket.
  ;; This flies in the face of the fundamental principle that a System
  ;; should really be an atomic whole, but that's the basic reality of
  ;; what I'm building here.
  {::log-chan (::log-chan opts)
   ::weald/logger (into {::chan (ig/ref ::log-chan)}
                        (::logger opts))
   ;; Note that this is really propagating the Server logs.
   ;; The Client logs are really quite different...though it probably
   ;; makes sense to also send those here, at least for an initial
   ;; implementation.
   ::propagate/monitor (into {::propagate/log-chan (ig/ref ::log-chan)}
                             (::monitor opts))
   :backend.web.server/web-server (::web-server opts)})

(defn ctor [opts]
  "Set up monitor/server/client all at once"
  (let [monitor (monitoring-ctor opts)
        server (server-ctor (into opts monitor))]
    (client-ctor (into opts server))))
