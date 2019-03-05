(ns backend.web.service
  (:require [aleph.http.server :as aleph]
            [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::debug? boolean?)

;;; Q: What are the legal options here?
(s/def ::environment #{:dev :prod})

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
  [service-map
   {:keys [:host :port]
    :or {host "127.0.0.1"
         port 10555}
    :as server-opts}]
  (let [server-atom (atom nil)
        handler (fn [& args]
                  ;; This should be the basic aleph handler.
                  ;; Its job, at least in theory, is to take a Ring
                  ;; Request and convert it into either a Response or
                  ;; a core.async that will yield that Response.
                  ;; In practice, that seems like I should set up the
                  ;; interceptor chain and call (execute) on it.
                  ;; According to the comments for
                  ;; https://github.com/pedestal/pedestal/pull/421
                  ;; there is now a public io.pedestal.interceptor.chain
                  ;; ns that's added `execute-only` to the public
                  ;; `execute` and `enqueue` functions.
                  ;; It's part of pedestal.interceptor rather than
                  ;; pedestal.service.
                  ;; `execute-only` is really for the sake of things
                  ;; like streaming interfaces.
                  ;; Which, of course, is extremely interesting for this
                  ;; use case.
                  (throw (ex-info "service-map handler called. What do I do with it?"
                                  {::params args})))]
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
           ::port]
    :or {environment :prod
         port 10555}}]
  {
   ;; nil means the default [servlet-interceptor] chain-provider.
   ;;
   ;; We can customize this also
   ::http/chain-provider chain-provider

   :env  environment

   ;; This would normally be a keyword indicating the default web server.
   ;;
   ;; Providing a function allows customization
   ::http/type build-server})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::chain-provider
  [_ {:keys [::debug?
             ;; FIXME: This needs to be a Component
             ::route-atom]
      :as opts}]
  (println "Setting up Pedestal")
  (let [base-service-map (build-basic-service-map opts)
        almost-runnable-service (if-not debug?
                                  (assoc base-service-map
                                         ::http/routes (route/expand-routes @route-atom))
                                  (-> base-service-map
                                      (merge {:env :dev
                                              ;; pointless under aleph
                                              :join? false
                                              ;; Use this to set routes to be reloadable without a
                                              ;; full (reset)
                                              ::http/routes #(route/expand-routes @route-atom)
                                              ;; Ignore CORS for dev mode
                                              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}})))]
    (-> almost-runnable-service
        http/create-server
        http/start)))
