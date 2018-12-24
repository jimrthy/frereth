(ns client.networking
  (:require [aleph.udp :as udp]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [frereth.cp
             [client :as client]
             [shared :as shared]]
            [frereth.cp.client.state :as client-state]
            [frereth.cp.message.specs :as msg-specs]
            [frereth.cp.shared
             [constants :as K]
             [crypto :as crypto]
             [specs :as shared-specs]]
            [frereth.weald
             [logging :as log]
             [specs :as weald]]
            [integrant.core :as ig]
            [manifold.stream :as strm])
  (:import java.net.InetAddress))

(s/fdef connect-to-server
  :args (s/cat :message-loop-name ::msg-specs/message-loop-name
               :my-long-key-pair ::shared/long-pair
               :log-state ::weald/state
               :logger ::weald/logger
               :socket-source strm/source?  ; Client will read from this to get messages for Child
               :server-name string?
               :server-long-term-public-key ::shared-specs/public-long
               :server-extension-vector (s/and vector?
                                               #(= (count %) K/extension-length))
               ;; Q: Do I have a spec already defined for this?
               :hosts (s/coll-of (s/tuple int? int? int? int?))
               :server-port any?  ; FIXME: Get a real spec for this
               ;; child calls this with byte-arrays
               :->child ::msg-specs/->child)
  :ret ::client-state/state)
(defn connect-to-server
  "This really belongs in CurveCP"
  [message-loop-name
   my-long-key-pair
   log-state
   logger
   socket-source
   server-name
   server-long-term-public-key
   server-extension-vector
   server-addresses
   server-port
   ->child]
  (let [log-state (log/info log-state
                            ::connect-to-server
                            "Starting a CurveCP client")
        key-dir "client-sample"
        nonce-key-resource (io/resource (str key-dir
                                             "/.expertsonly/noncekey"))]
    (when-not nonce-key-resource
      (println "Building a new nonce-key")
      (crypto/new-nonce-key! key-dir))

    (let [server-extension (byte-array server-extension-vector)
          server-binary-name (shared/encode-server-name server-name)
          result (client/ctor {::msg-specs/->child ->child
                               ::shared/my-keys {::shared/keydir key-dir
                                                 ::shared/long-pair my-long-key-pair
                                                 ::shared-specs/srvr-name server-binary-name}
                               ::msg-specs/message-loop-name message-loop-name
                               ::client-state/chan<-server socket-source
                               ::client-state/server-extension server-extension
                               ::client-state/server-ips (map #(InetAddress/getByAddress (byte-array %))
                                                              server-addresses)
                               ::client-state/server-security {::shared-specs/srvr-name server-binary-name
                                                               ::shared-specs/srvr-port server-port
                                                               ::shared-specs/public-long server-long-term-public-key}}
                              logger)]
      ;; This implementation is almost copy/pasted from the raw-client function in tests/frereth_cp/test_factory.clj
      ;; It cannot suffer the limitations that has of running synchronously in the same thread as the server.
      (client/start! result)
      result)))

(defmethod ig/init-key ::socket
  [_ {:keys [::port]
      ;; Note that the server-port actually needs to be shared
      :or {port 41425}
      :as opts}]
  (println "Starting client socket on port" port "(this may take a bit)")
  (let [result @(udp/socket {:port port})]
    (println "Client UDP socket ready to connect")
    result))

(defmethod ig/halt-key! ::socket
  [_ socket]
  (.close socket))

(defmethod ig/init-key ::connection
  [_ {:keys [::msg-specs/message-loop-name
             ::shared/long-pair
             ::socket
             ;; FIXME: Start back here with this
             ::server-addresses
             ::server-port
             ::weald/logger
             ::weald/state]
      server-name ::shared-specs/srvr-name
      server-long-term-public-key ::shared-specs/public-long
      server-extension-vector ::client-state/server-extension
      :as opts}]
  (let [log-state (log/init ::connection)
        ->child nil
        child-spawner! (fn []
                         (throw (RuntimeException. "What should this do?")))]
    (connect-to-server message-loop-name
                       long-pair
                       log-state
                       logger
                       socket
                       server-name
                       server-long-term-public-key
                       server-extension-vector
                       server-addresses
                       server-port
                       ->child)))

(defmethod ig/halt-key! ::connection
  [_ connection]
  ;; FIXME: This really needs to signal the child to
  ;; wrap up whatever it's doing so it can send the "all
  ;; done" signal to the server
  (println "Surely there's something to do here"))
