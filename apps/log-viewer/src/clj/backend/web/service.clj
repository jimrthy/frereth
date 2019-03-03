(ns backend.web.service
  (:require [aleph.http.server :as aleph]
            [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [io.pedestal.http :as http]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;; Q: What are the legal options here?
(s/def ::environment #{:prod})

;;; Q: Is this defined anywhere in Pedestal?
(s/def ::service-map-sans-handler map?)

(s/def ::service-map (s/merge ::service-map-sans-handler
                              (s/keys :req [::handler])))

;; Q: What should this really return?
(s/def ::server any?)

(s/def ::start-fn (s/fspec :args nil :ret ::server))

(s/def ::stop-fn (s/fspec :args nil :ret ::server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef chain-provider
  :args (s/cat :service-map ::service-map-sans-handler)
  :ret ::service-map)
(defn chain-provider
  "Provide all functionality to execute the interceptor chain

  This includes extracting the base :request into context.

  These funcions/objects are added back into the service map for use
  within the server-fn.

  See io.pedestal.http.impl.servlet-interceptor.clj as an example.

  Interceptor chains:
  * Terminate based on the list of :terminators in the context.
  * Call the list of functions in :enter-async when going async. Use
  these to taggle async mode on the container.
  * Will use the fn at async? to determine if the chain has been
  operating in async mode (so the container can handle on the
  outbound)"
  [service-map]
  (assoc service-map ::handler (throw (RuntimeException. "Write this"))))

(s/fdef build-server
  :args (s/cat :service-map ::service-map
               :server-opts ::server-opts)
  :ret (s/keys :req-un [::server ::start-fn ::stop-fn]))
(defn build-server
  [{:keys [::handler]
    :as service-map}
   {:keys [:host :port]
    :or {host "127.0.0.1"
         port 10555}
    :as server-opts}]
  (let [server (aleph/start-server handler {:port port
                                            :socket-address host})]
    {:server server
     ;; Q: Does it make any sense to try to restart the server?
     :start-fn (fn [])
     :stop-fn (fn []
                (.close server))}

    (throw (RuntimeException. "Write this"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn build-definition
  [{:keys [::environment
           ::port]
    :or {environment :prod
         port 10555}}]
  {:env  environment

   ;; This would normally be a keyword indicating the default web server.
   ;;
   ;; Providing a function allows customization
   ::http/type build-server

   ;; nil means the default [servlet-interceptor] chain-provider.
   ;;
   ;; We can customize this also
   ::http/chain-provider chain-provider
   })
