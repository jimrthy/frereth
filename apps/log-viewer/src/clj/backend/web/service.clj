(ns backend.web.service
  (:require [aleph.http.server :as aleph]
            [aleph.netty]
            [backend.web.routes :as routes]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.spec.alpha :as s]
            [clojure.core.async]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [manifold.deferred :as dfrd]
            [ring.core.protocols])
  (:import [java.net InetSocketAddress SocketAddress]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::debug? boolean?)

;;; Q: What are the legal options here?
(s/def ::environment #{:dev :prod})

;;; Q: Worth enumerating the list of legal codes?
(s/def :http/status (s/and integer?
                           #(>= % 100)))

;; Ring allows an instance of
;; ring.core.protocols/StreamableResponseBody.
;; By default, that's fulfilled by the classes/interfaces
;; #{String ISeq File InputStream}
;; Those happen to be the same types that satisfy
;; the Pedestal body requirements.
(s/def :ring/body #(satisfies? ring.core.protocols/StreamableResponseBody %))
(s/def :pedestal/body (s/or :string string?
                            :seq #(instance? clojure.lang.ISeq %)
                            :file #(instance? java.io.File %)
                            :input-stream #(instance? java.io.InputStream %)))

(s/def :pedestal/headers (s/or :map (s/map-of string? string?)
                               :seq (s/coll-of string?)))
(s/def :ring/headers (s/map-of string? string?))

;; There's one major discrepancy between the Ring and Pedestal specs.
;; Under Ring, the uri:
;;   The request URI, excluding the query string and the "?" separator.
;;   Must start with "/".
(s/def :ring/uri (s/and string?
                        #(= (first %) \/)
                        #(not (some #{\?} %))))
(s/def :ring/request (s/keys :req-un [::scheme
                                      ::server-name
                                      ::server-port
                                      :ring/uri]))

(s/def ::async-supported? boolean?)
;; Pedestal request also includes path-info:
;;  Request path, below the context path. Always at least "/", never
;;  an empty string.
(s/def :pedestal/path-info :ring/uri)
;; Under Pedestal, the uri is:
;;  The part of this request's URL *from the protocol name* up to the
;;  query string in the first line of the HTTP request
;; i.e. "http://nowhere.org:port/foo/bar/baz"
;; TODO: Spec this better
(s/def :pedestal/uri string?)
(s/def :pedestal/request (s/keys :req-un [::async-supported?
                                          :pedestal/path-info
                                          :pedestal/uri]))

(s/def :pedestal/response (s/keys :req-un [:http/status]
                                  :opt-un [:ring/body
                                           :pedestal/headers]))
(s/def :ring/response (s/keys :req-un [:ring/headers
                                       ::status]
                              :opt-un [:ring/body]))

;;; Q: Is this defined anywhere in Pedestal?
(s/def ::service-map-sans-handler map?)

(s/def ::service-map (s/merge ::service-map-sans-handler
                              (s/keys :req [::handler])))

;; This is also a java.io.Closeable
(s/def ::server #(satisfies? aleph.netty.AlephServer %))

(s/def ::start-fn (s/fspec :args nil :ret ::server))

(s/def ::stop-fn (s/fspec :args nil :ret ::server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef translate-request
  :args (s/cat :ring-request :ring/request)
  :ret :pedestal/request)
(defn translate-request
  "Convert aleph's Ring Request into a Pedestal request map"
  [{:keys [:scheme :server-name :server-port :uri] :as ring-request}]
  (let [my-uri (str scheme
                    "://" server-name
                    ":" server-port
                    uri)]
    {:request (assoc ring-request
                     :async-supported? true
                     :path-info uri
                     :uri my-uri)}))

(s/fdef translate-response-context
  :args (s/cat :response :pedestal/response)
  :ret :ring/response)
(defn translate-response-context
  "Convert a Pedestal response into the Ring Response aleph expects"
  [{:keys [:body :headers :status]
    :as pedestal-response}]
  (cond (map? headers) pedestal-response
        (nil? headers) (assoc pedestal-response :headers {})
        :else (update pedestal-response :headers #(apply hash-map %))))

(def request-printer (agent nil))

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
                                   ;; FIXME: As long as I'm going to do this, it needs
                                   ;; to be thread-safe.
                                   ;; These things come in hot/fast enough to get totally
                                   ;; jumbled.
                                   (send request-printer
                                         (fn [_]
                                           (println "Incoming request")
                                           (pprint ring-request)
                                           _))
                                   (let [initial-context (translate-request ring-request)
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
    ;; Type really doesn't matter in the :server key
    ;; It's really designed for the java servlet model where
    ;; we initialize it here, then start/stop-fn modifies its state
    {:server server-atom
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
                                           :socket-address (if (instance? SocketAddress host)
                                                             host
                                                             (InetSocketAddress. host port))}]
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
  [{:keys [::debug?
           ::port
           ::routes/handler-map]
    :or {port 10555}}]
  (println "build-basic-service-map debug? =" debug?)
  (let [raw
        {
         ;; nil means the default [servlet-interceptor] chain-provider.
         ;;
         ;; This sets up the handler that the server will use to actually
         ;; execute the Interceptor Chain
         ::http/chain-provider chain-provider

         :env  (if-not debug? :prod :debug)

         ;; pointless under aleph
         :join? false

         ::http/routes (::routes/routes handler-map)

         ::http/secure-headers (let [default-csp {:object-src "'none'"
                                                  ;; Default uses 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:
                                                  ;; c.f. https://github.com/pedestal/pedestal/blob/master/service/src/io/pedestal/http/secure_headers.clj
                                                  ;; Q: What are the odds that clojurescript uses 'unsafe-eval'?
                                                  ;; Q: What about bootstrap clojurescript?
                                                  ;; TODO: Work through how to set a nonce.
                                                  ;; (I've done this before: add an early Interceptor
                                                  ;; that adds it to the Context so handlers can
                                                  ;; add it to script tags, then associate it with
                                                  ;; the secure headers map before converting it to
                                                  ;; a string. Or just append it to the standard
                                                  ;; default string).
                                                  ;; This static approach isn't great.
                                                  ;; Honestly, need to pull out the default "secure header"
                                                  ;; interceptor and replace it with one that does
                                                  ;; the Right Thing.
                                                  ;; Of course, that also means manipulating all the
                                                  ;; script tags associated with this browser SESSION,
                                                  ;; so that's its own can of worms.
                                                  :script-src #_ "'self' 'strict-dynamic' https: http:"
                                                  ;; unsafe-inline is a Bad Thing.
                                                  ;; It discards one of the prime advantages of setting
                                                  ;; up CSP in the first place.
                                                  ;; For that matter, this really should be tucked away
                                                  ;; behind https, so http shouldn't be allowed.
                                                  ;; That's another bigger-picture issue.
                                                  "'self' 'unsafe-inline' https: http:"}
                                     worker-csp (cond-> default-csp
                                                  debug? (update :script-src #(str % " 'unsafe-eval'")))]
                                 {:content-security-policy-settings worker-csp})

         ;; This would normally be a keyword indicating the default web server.
         ;; Providing a function allows customization
         ::http/type build-server}]
    (apply assoc raw
           (if (.exists (io/file "dev-output/js"))
             [::http/file-path "dev-output"]
             ;; Q: What are the odds that this will work?
             [::http/resource-path "/"]))))

(defn build-definition
  [{:keys [::debug?
           ::routes/handler-map]
    :as opts}]
  (println "Setting up Pedestal in" (if debug? "" "not") "debug mode")
  (when-not handler-map
    (throw (RuntimeException. "Need a router Component")))

  (let [base-service-map (build-basic-service-map opts)]
    (cond-> base-service-map
      debug?
      (-> (merge {:env :dev
                  ;; Ignore CORS for dev mode
                  ::http/allowed-origins {:creds true :allowed-origins (constantly true)}})
          http/default-interceptors
          ;; TODO: replace(?) middlewares/resource with
          ;; (middlewares/fast-resource resource-path)
          ;; see pedestal/service/src/io/pedestal/http.clj

          ;; Q: Do I want these?
          ;; A: There's io.pedestal.http.cors/dev-allow-origin and
          ;; servlet-interceptor/exception-debug
          ;; Even though I'm not using servlets, it seems like I might
          ;; want both of those.
          ;; Though dev-allow-origin sounds like it might be a bad
          ;; idea (want to keep dev mode as close to prod as feasible)
          #_http/dev-interceptors
          (update ::http/interceptors (fn [chain]
                                        (let [err-log (interceptor/interceptor {:name ::outer-error-logger
                                                                                :error (fn [ctx ex]
                                                                                         (println (str "oopsie\n"
                                                                                                       (with-out-str (pprint (ex-data ex)))
                                                                                                       "Caused by\n"
                                                                                                       (with-out-str (pprint ctx))))
                                                                                         (assoc ctx ::chain/error ex))})
                                              dfrd->async (interceptor/interceptor {:name ::dfrd->async
                                                                                    :leave (fn [{:keys [:response]
                                                                                                 :as ctx}]
                                                                                             (if response
                                                                                               (do
                                                                                                 (println (str "Checking for deferred->async in\n"
                                                                                                               response ",\na "
                                                                                                               (class response)))
                                                                                                 #_(update ctx :response
                                                                                                           ;; If we have a manifold.deferred, convert
                                                                                                           ;; it into a core.async channel.
                                                                                                           ;; Pedestal waits for those if an
                                                                                                           ;; interceptor returns that instead
                                                                                                           ;; of a Context.
                                                                                                           ;; In theory.
                                                                                                           ;; That didn't work out so well
                                                                                                           ;; for the websocket handler.
                                                                                                           ;; The problem is that it is the body
                                                                                                           ;; that can be an async channel rather
                                                                                                           ;; than the response.
                                                                                                           (fn [response]
                                                                                                             (cond-> response
                                                                                                               (dfrd/deferred? response) (async/go
                                                                                                                                           @response))))
                                                                                                 (update-in ctx [:response :body]
                                                                                                            (fn [body]
                                                                                                           (cond-> body
                                                                                                             (dfrd/deferred? body) (async/go
                                                                                                                                     @body)))))
                                                                                               (do
                                                                                                 (println "Missing response in context:")
                                                                                                 (pprint ctx)
                                                                                                 ctx)))})]
                                          (concat [err-log servlet-interceptor/terminator-injector]
                                                  (conj chain dfrd->async)))))))))

(comment
  (let [base-service-map (build-basic-service-map {::routes/handler-map {::routes/routes (routes/build-pedestal-routes nil (atom nil))}})
        default-intc (http/default-interceptors base-service-map)]
    (keys default-intc)
    (dissoc default-intc ::http/routes))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::web-service
  [_ opts]
  (-> opts
      build-definition
      http/create-server
      http/start))

(comment
  (let [handler-map  {::routes/handler-map {::routes/routes #{["/" :get (fn [req] "hi") :route-name ::default]}}}]
    (comment (build-basic-service-map handler-map)
             (-> handler-map build-basic-service-map http/default-interceptors)
             (-> handler-map build-basic-service-map http/default-interceptors :io.pedestal.http/interceptors)
             ;; This doesn't add any interceptors
             (build-definition handler-map))
    ;; create-server adds the content-negotiation interceptor, but that's about
    ;; file name extensions rather than anything like the content accepted header.
    (-> handler-map build-definition http/create-server ::http/interceptors))
  )

(defmethod ig/halt-key! ::web-service
  [_ {:keys [::http/stop-fn]
      :as service-map}]
  (println "Trying to halt web-service")
  (pprint service-map)
  (if stop-fn
    (stop-fn)
    (println "Nothing to stop")))
