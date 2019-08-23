(ns tracker.server-components.http-server
  (:require
    [clojure.pprint :refer [pprint]]
    [io.pedestal.http :as http]
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
    [tracker.server-components.middleware :refer [middleware]]))

(def routes
  (pedestal/routing-interceptor
   (reitit.http/router
    [["/swagger.json" {:get {:no-doc true
                             :swagger {:info {:title "my-api"
                                              :description "with pedestal & reitit-http"}}
                             :handler (swagger/create-swagger-handler)}}]
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
     {:path "/"
      :config {:validatorUrl nil
               :operationsSorter "alpha"}})
    (ring/create-resource-handler)
    (ring/create-default-handler))))

(defstate service-map
  :start (let [{:keys [:io.pedestal/http]} config
               {:keys [:port]} http  ; TODO: nested destructuring
               ;; TODO: define a default :port value
               local-config {:env :dev   ; Q: ?
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
           (log/info "Starting HTTP Server with config " (with-out-str (pprint local-config))
                     " based on keys "
                     (keys config)
                     "\nin\n"
                     (with-out-str (pprint config))
                     "\nbased on\n"
                     http)
           (-> local-config
               (http/default-interceptors)
               ;; use the reitit router
               (pedestal/replace-last-interceptor routes)
               ;; Q: How to handle this only in dev mode?
               (http/dev-interceptors)
               (http/create-server))))

(defstate http-server
  :start (http/start service-map)
  :stop (http/stop service-map))
