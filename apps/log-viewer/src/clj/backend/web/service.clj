(ns backend.web.service
  (:require [aleph.http.server :as aleph]
            [backend.web.routes :as routes]
            [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor.chain :as chain]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::debug? boolean?)

;;; Q: What are the legal options here?
(s/def ::environment #{:dev :prod})

;;; Q: Worth enumerating the list of legal codes?
(s/def :http/status (s/and integer?
                           #(>= % 100)))

;; TODO: Spec this out.
;; Technically, ring allows an instance of
;; ring.core.protocols/StreamableResponseBody.
;; By default, that's fulfilled by the classes/interfaces
;; #{String ISeq File InputStream}
;; Those happen to be the same types that satisfy
;; the Pedestal body requirements.
(s/def :ring/body any?)

(s/def :pedestal/headers (s/or :map (s/map-of string? string?)
                               :seq (s/coll-of string?)))
(s/def :ring/headers (s/map-of string? string?))

(s/def ::pedestal-response (s/keys :req-un [:http/status]
                                   :opt-un [:ring/body
                                            :pedestal/headers]))
(s/def ::ring-response (s/keys :req-un [:ring/headers
                                        ::status]
                               :opt-un [:ring/body]))

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

(defn build-request-context
  [{:keys [:uri] :as ring-request}]
  (let [direct-translation (select-keys ring-request [:body
                                                      :query-string
                                                      :request-method])]
    {:request (assoc direct-translation
                     :path-info uri
                     :async-supported? true)}))

(s/fdef translate-response-context
  :args (s/cat :response ::pedestal-response)
  :ret ::ring-response)
(defn translate-response-context
  [{:keys [:body :headers :status]
    :as pedestal-response}]
  (cond (map? headers) pedestal-response
        (nil? headers) (assoc pedestal-response :headers {})
        :else (update pedestal-response :headers #(apply hash-map %))))

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
  (let [interceptors (::http/interceptors service-map)]
    (assoc service-map ::handler (fn [ring-request]
                                   (let [initial-context (build-request-context ring-request)
                                         resp-ctx (chain/execute initial-context
                                                                 interceptors)]
                                     (translate-response-context (:response resp-ctx)))))))

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
  (let [server-atom (atom nil)]
    {:server server-atom  ;; Q: Does the type matter here?
     :start-fn (fn []
                 (swap! server-atom
                        (fn [existing]
                          (if-not existing
                            ;; aleph doesn't seem to have any concept of
                            ;; setting up a server object then
                            ;; starting/stopping it separately.
                            ;; Which makes sense for a functional
                            ;; environment.
                            (let [details {:port port
                                           :socket-address host}]
                              (aleph/start-server handler details))
                            (do
                              (println "WARNING: Trying to restart existing server")
                              existing)))))
     :stop-fn (fn []
                (swap! server-atom
                       (fn [server]
                         (when server
                           (.close server)
                           nil))))}))

(defn build-basic-service-map
  [{:keys [::environment
           ::port
           ::routes/handler-map]
    :or {environment :prod
         port 10555}}]
  {
   ;; nil means the default [servlet-interceptor] chain-provider.
   ;;
   ;; This sets up the handler that the server will use to actually
   ;; execute the Interceptor Chain
   ::http/chain-provider chain-provider

   :env  environment

   ;; pointless under aleph
   :join? false

   ::http/routes (::routes/routes handler-map)

   ;; This would normally be a keyword indicating the default web server.
   ;;
   ;; Providing a function allows customization
   ::http/type build-server})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::chain-provider
  [_ {:keys [::debug?
             ::routes/handler-map]
      :as opts}]
  (println "Setting up Pedestal")
  (when-not handler-map
    (throw (RuntimeException. "Need a router Component")))
  (let [base-service-map (build-basic-service-map opts)
        almost-runnable-service (if-not debug?
                                  base-service-map
                                  (-> base-service-map
                                      (merge {:env :dev
                                              ;; Ignore CORS for dev mode
                                              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}})))]
    (-> almost-runnable-service
        http/create-server
        http/start)))
