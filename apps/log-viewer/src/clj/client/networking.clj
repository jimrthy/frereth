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
            [manifold.stream :as strm])
  (:import java.net.InetAddress))

(s/fdef connect-to-server
  :args (s/cat :my-long-key-pair ::shared/long-pair
               :log-state ::weald/state
               :logger-init (s/fspec :args nil :ret ::weald/logger)
               :socket-source strm/source?  ; Client will read from this to get messages for Child
               :server-name string?
               :server-extension-vector (s/and vector?
                                               #(= (count %) K/extension-length))
               :server-long-term-public-key ::shared-specs/public-long
               ;; Q: Do I have a spec already defined for this?
               :hosts (s/coll-of (s/tuple int? int? int? int?))
               :->child ::msg-specs/->child  ; child calls this with byte-arrays
               :child-spawner! ::msg-specs/child-spawner!)
  :ret ::client-state/state)
(defn connect-to-server
  "This really belongs in CurveCP"
  [message-loop-name
   my-long-key-pair
   log-state
   logger-init  ; Passing this instead of a logger feels odd
   socket-source
   server-name
   server-long-term-public-key
   server-extension-vector
   server-addresses
   server-port
   ->child
   child-spawner!]
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
          result (client/ctor {::client-state/chan<-server socket-source
                               ::weald/state log-state
                               ::msg-specs/->child ->child
                               ::msg-specs/child-spawner! child-spawner!
                               ::msg-specs/message-loop-name message-loop-name
                               ::shared/my-keys {::shared/keydir key-dir
                                                 ::shared/long-pair my-long-key-pair
                                                 ::shared-specs/srvr-name server-binary-name}
                               ::client-state/server-extension server-extension
                               ::client-state/server-ips (map #(InetAddress/getByAddress (byte-array %))
                                                              server-addresses)
                               ::client-state/server-security {::shared-specs/srvr-name server-binary-name
                                                               ::shared-specs/srvr-port server-port
                                                               ::shared-specs/public-long server-long-term-public-key}}
                              logger-init)]
      ;; This implementation is almost copy/pasted from the raw-client function in tests/frereth_cp/test_factory.clj
      ;; It cannot suffer the limitations that has of running synchronously in the same thread as the server.
      (client/start! result)
      result)))
