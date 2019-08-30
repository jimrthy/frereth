(ns tracker.server-components.http-server
  (:require
    [clojure.pprint :refer [pprint]]
    [io.pedestal.http :as http]
    [io.pedestal.http.csrf :as csrf]
    [mount.core :refer [defstate]]
    [muuntaja.core :as m]
    [reitit.coercion.spec]
    [reitit.dev.pretty :as pretty]
    [reitit.http.coercion :as coercion]
    [reitit.http.interceptors.exception :as exception]
    [reitit.http.interceptors.multipart :as multipart]
    [reitit.http.interceptors.muuntaja :as muuntaja]
    [reitit.http.interceptors.parameters :as parameters]
    [reitit.http.spec :as spec]
    [reitit.pedestal :as pedestal]
    [reitit.ring :as ring]
    [reitit.swagger :as swagger]
    [reitit.swagger-ui :as swagger-ui]

    ;; TODO: convert this to frereth.weald
    [taoensso.timbre :as log]
    [tracker.server-components.config :refer [config]]
    [tracker.server-components.middleware :as middleware]))

(def router
  (pedestal/routing-interceptor
   (reitit.http/router
    [[;; Also want this to be the handler for index.html
      ;; And probably all the similar variants.
      ;; Q: How does reitit handle that?
      "/" {:get {:no-doc true
                 :handler (fn [{:keys [::csrf/anti-forgery-token]
                                :as context}]
                            ;; Q: What about setting up a real Pedestal
                            ;; interceptor?
                            ;; One that gets the full context, including
                            ;; the standard request map, and adds a response
                            ;; to signal that it's been handled?
                            ;; Based on that approach, io.pedestal.http/html-body
                            ;; should set the headers appropriately
                            {:status 200
                             ;; Contrary to my expectations, this is
                             ;; case-sensitive
                             ;; Q: Should I try to match whatever handlers
                             ;; came in with the request?
                             :headers {"Content-Type" "text/html"}
                             :body
                             (middleware/index anti-forgery-token)})}}]
     ["/api" {}
      ;; TODO: Look into
      ;; https://github.com/metosin/reitit/blob/master/examples/pedestal-swagger/src/example/server.clj
      ;; and really study what's going on here
      ["/math"
       {:swagger {:tags ["math"]}}
       ["/plus"
        {:get {:summary "plus with spec query parameters"
               :parameters {:query {:x int? :y int?}}
               :responses {200 {:body {:total int?}}}
               :handler (fn [{{{:keys [x y]} :body} :parameters}]
                          {:status 200
                           :body {:total (+ x y)}})}}]]]
     ["/echo" {:get {:swagger {:info {:title "Echo handler"
                                      :description "To see what the REQUEST looks like"}}
                     :handler (fn [{:keys [:body
                                           :context-path
                                           :form-params
                                           :headers
                                           :params
                                           :path-info
                                           :path-params
                                           :query-params
                                           :query-string
                                           :muuntaja/request
                                           :request-method
                                           :scheme
                                           :server-name
                                           :servlet-request
                                           :uri]
                                    :as context}]
                                (comment (log/info "Trying to echo" context
                                                   "\nkeys:" (keys context)))
                                (let [body (slurp body)
                                      response
                                      {:status 200
                                       :body {:request (assoc (select-keys context [:context-path
                                                                                    :form-params
                                                                                    :headers
                                                                                    :params
                                                                                    :path-info
                                                                                    :path-params
                                                                                    :query-params
                                                                                    :query-string
                                                                                    :muuntaja/request
                                                                                    :request-method
                                                                                    :scheme
                                                                                    :server-name
                                                                                    :uri])
                                                              :body body)}}]
                                  (comment (log/debug "Trying to return\n"
                                                      (with-out-str (pprint response))))
                                  response))}}]
     ["/swagger.json" {:get {:no-doc true
                             :swagger {:info {:title "my-api"
                                              :description "with pedestal & reitit-http"}}
                             :handler (swagger/create-swagger-handler)}}]
     ;; Q: Does the anti-forgery-token come from middleware/wrap-defaults?
     ["/wslive.html" {:get {:swagger {:info {:title "w-s-live"
                                             :description "for interacting with workspaces"}}
                            :handler (fn [{{:keys [:anti-forgery-token]} :headers
                                           :as context}]
                                       {:status 200
                                        :body (middleware/wslive anti-forgery-token)})}}]]
    {;;reitit.interceptor/transform dev/print-context-diffs  ;; pretty context diffs
     :validate spec/validate ;; enable spec validation for route data
     ;;reitit.spec/wrap spell/closed  ;; strict top-level validation
     ;; Q: Should this be dev-time only? If so, how?
     :exception pretty/exception
     :data {:coercion reitit.coercion.spec/coercion
            ;; Fast format negotiation, encoding, and encoding
            ;; This is "the boring library everyone should use"
            :muuntaja m/instance
            :interceptors [swagger/swagger-feature
                           ;; query-params and form-params
                           (parameters/parameters-interceptor)
                           ;; content-negotiation
                           (muuntaja/format-negotiate-interceptor)
                           ;; encoding response body
                           (muuntaja/format-response-interceptor)
                           ;; exception handling
                           (exception/exception-interceptor)
                           ;; decoding request body
                           (muuntaja/format-request-interceptor)
                           ;; coercing response bodies
                           (coercion/coerce-response-interceptor)
                           ;; coercing request parameters
                           (coercion/coerce-request-interceptor)
                           ;; multipart
                           (multipart/multipart-interceptor)]}})
   ;; optional default ring(?) handler (if no routes have matched)
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/swagger-ui"
      :config {:validatorUrl nil
               :operationsSorter "alpha"}})
    (ring/create-resource-handler)
    (ring/create-default-handler))))

(defstate service-map
  :start (let [{{:keys [:port]
                 :as http} :io.pedestal/http} config
               local-config {:env :dev   ; Q: ?
                             ::http/enable-csrf {:cookie-token true
                                                 :error-handler (fn [context]
                                                                  (log/error "CSRF attack detected"
                                                                             (with-out-str (pprint context)))
                                                                  ;; Q: What's the proper response here?
                                                                  {:status 401
                                                                   :body "Authentication required"})}
                             ::http/join? false
                             ::http/port port
                             ;; Using reitit for routing
                             ::http/routes []
                             ;; Allow serving the swagger-ui styles and scripts from self
                             ::http/secure-headers {:content-security-policy-settings {:default-src "'self'"
                                                                                       ;; really?!
                                                                                       :style-src "'self' 'unsafe-inline'"
                                                                                       :script-src "'self' 'unsafe-inline'"}}
                             ::http/type :immutant}]
           (log/info "Creatting HTTP Server with config " (with-out-str (pprint local-config))
                     " based on keys "
                     (keys config)
                     "\nin\n"
                     (with-out-str (pprint config))
                     "\nbased on\n"
                     http)
           (-> local-config
               (http/default-interceptors)
               ;; use the reitit router
               (pedestal/replace-last-interceptor router)
               ;; Q: How to handle this only in dev mode?
               (http/dev-interceptors)
               (http/create-server))))

(defstate ^{:on-reload :noop} http-server
  ;; Q: What's a good way to write this?
  :start (reduce (fn [acc delay-time]
                   (log/debug "Delaying" delay-time "ms before trying to start http server")
                   (Thread/sleep delay-time)
                   (try
                     (let [success
                           (http/start service-map)]
                       (log/debug "Succeeded on try #" acc)
                       (reduced success))
                     (catch java.net.BindException ex
                       (log/warn "Web server attempt #" acc " failed.")
                       ;; Q: Is there a good way to really and truly
                       ;; indicate failure?
                       (inc acc))
                     (catch RuntimeException ex
                       (log/warn ex "RuntimeException. Specifically" (type ex))
                       (inc acc))
                     (catch Exception ex
                       (log/error ex "a" (type ex))
                       (inc acc))))
                 1
                 [1 10 100 1000])
  :stop (do
          (log/warn "Web server stopping")
          (let [stopped (http/stop service-map)]
            ;; This looks correct.
            ;; Q: Would it help to build in a delay here until we can no longer
            ;; connect to (::http/port stopped)?
            (log/debug "Calling http/stop returned" stopped)
            stopped)))

(comment
  http-server)
