(ns server.networking
  (:require [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [frereth.cp.message.specs :as msg-specs]
            [frereth.cp
             [server :as server]
             [shared :as shared]]
            [frereth.cp.server.state :as srvr-state]
            [frereth.cp.shared
             [constants :as K]
             [specs :as shared-specs]]
            [frereth.weald
             [logging :as log]
             [specs :as weald]]
            [integrant.core :as ig]
            [manifold
             [executor :as exec]
             [stream :as strm]])
  (:import clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::cp-server ::server/pre-state-options)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Helpers

(s/fdef server-options
  :args (s/cat :logger ::weald/logger
               :log-state-atom ::weald/state-atom
               :->child ::msg-specs/->child
               :server-extension-vector (s/and vector?
                                               #(= (count %) K/extension-length))
               :server-name string?)
  :ret ::cp-server)
(defn server-options
  [logger log-state-atom ->child server-extension-vector server-name]
  (let [child-id-atom (atom 0)
        executor (exec/fixed-thread-executor 4)
        server-extension (byte-array server-extension-vector)
        server-binary-name (shared/encode-server-name server-name)]
    {::weald/logger logger
     ::weald/state-atom log-state-atom
     ::msg-specs/->child ->child
     ::msg-specs/child-spawner! (fn [io-handle]
                                  (swap! log-state-atom
                                         #(log/flush-logs! logger
                                                           (log/debug (log/clean-fork % ::child-spawner!)
                                                                      ::top))))
     ::shared/extension server-extension
     ::shared/my-keys #::shared{::shared-specs/srvr-name server-binary-name
                                :keydir "server-sample"}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef build-server
  :args (s/cat :logger ::weald/logger
               :log-state-atom ::weald/state-atom
               :child-> ::msg-specs/->child
               :server-extension-vector (s/and vector?
                                               #(= (count %) K/extension-length))
               :server-name string?
               ;; It seems odd to split these up.
               ;; Unless maybe they're the read/write portions of socket stream?
               ;; For my original tests, trying to use a single stream for this
               ;; wreaked havoc on things.
               ;; Probably because it wasn't a duplex stream.
               :socket-source strm/source?
               :socket-sink strm/sink?)
  :ret ::server/pre-state)
(defn build-server
  "Set up the server definition"
  [logger log-state-atom ->child server-extension-vector server-name socket-source socket-sink]
  (try
    (swap! log-state-atom #(log/info %
                                     ::build-server
                                     "Trying to construct a server"
                                     {::weald/logger logger}))
    (let [base-options (server-options logger
                                       log-state-atom
                                       ->child
                                       server-extension-vector
                                       server-name)
          actual-options (into base-options
                          {::srvr-state/client-read-chan {::srvr-state/chan (:backend.system/udp-socket socket-source)}
                           ::srvr-state/client-write-chan {::srvr-state/chan (:backend.system/udp-socket socket-sink)}})]
      (server/ctor actual-options))
    (catch ExceptionInfo ex
      (try
        (if log-state-atom
          (do
            (swap! log-state-atom #(log/exception %
                                                  ex
                                                  ::build-server))
            (swap! log-state-atom #(log/flush-logs! logger %)))
          (println "Missing log state atom. Possibly related to:" ex))
        (catch Exception ex1
          (println "Double jeapordy failure:\n"
                   ex1
                   "\ntrying to flush-logs! to report\n"
                   ex
                   "\nto"
                   logger)
          (throw ex1)))
      (throw ex))))

(defmethod ig/init-key ::server
  [_ {:keys [::weald/logger
             ::msg-specs/->child
             ::extension-vector
             ::my-name
             ::socket]
      log-state-atom ::weald/state-atom
      :as opts}]
  (swap! log-state-atom #(log/debug %
                                    ::init-server
                                    "init-key"
                                    {::weald/logger logger
                                     ::options opts}))
  (try
    (let [inited (build-server logger
                               log-state-atom
                               ->child
                               extension-vector
                               my-name
                               socket
                               socket)]
      (swap! log-state-atom #(log/info %
                                       ::init-server
                                       "Component constructed. Ready to Start"
                                       {::keys (keys inited)
                                        ::inited inited}))
      (assoc opts
             ::cp-server (server/start! (assoc inited
                                               ::weald/state @log-state-atom))))
    (catch Exception ex
      (println "Oops" ex)
      (swap! log-state-atom #(log/exception %
                                            ex
                                            ::init-server))
      ;; Q: close the socket on a failure?
      ;; A: No. We don't own it.
      opts)))

(defmethod ig/halt-key! ::server
  [_ started]
  ;; Q: Better to leave the previous server around for post-mortems?
  ;; Or just remove that key completely so it's obvious that this
  ;; has been stopped?
  ;; The latter option just means we need to grab the state before
  ;; calling halt, which is probably a better idea anyway.
  ;; Neither option matters. This gets called for side-effects,
  ;; so the return value is correctly ignored.
  (when-let [server (::cp-server started)]
    (server/stop! server)))
