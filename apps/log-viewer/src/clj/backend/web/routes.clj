(ns backend.web.routes
  (:require
   [backend.web.handlers :as handlers]
   [bidi.bidi :as bidi]
   [bidi.ring :as ring]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [integrant.core :as ig]
   [io.pedestal.http.content-negotiation :as conneg]
   [io.pedestal.http.route :as route]
   [renderer.sessions :as sessions])
  (:import clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;; TODO: Surely there's a spec for this somewhere.
;;; It's a s/or of tuples.
;;; Basic idea is [path verb interceptors (optional route-name-clause) (optional constraints)]
(s/def ::route any?)
(s/def ::route-set (s/coll-of ::route))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Interceptors
;;;; Q: Do these belong in their own ns?

(def error-logger
  "Interceptor for last-ditch error logging efforts"
  {:name ::error-handler
   :error (fn [ctx ex]
            (println "**** Ooopsie")
            (pprint (ex-data ex))
            (println "Caused by")
            (pprint ctx)
            (assoc ctx :io.pedestal.interceptor.chain/error ex))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal

(s/fdef build-pedestal-routes
  :args (s/cat :lamport-clock ::lamport/clock
               :session-atom ::sessions/session-atom)
  :ret ::route-set)
(defn build-pedestal-routes
  [lamport-clock
   session-atom]
  (let [content-neg-intc (conneg/negotiate-content "text/html" "application/edn" "text/plain")
        definition
        #{["/" :get [content-neg-intc handlers/index-page] :route-name ::default]
          ["/index" :get [content-neg-intc handlers/index-page] :route-name ::default-index]
          ["/index.html" :get [content-neg-intc handlers/index-page] :route-name ::default-html]
          ["/index.php" :get [content-neg-intc handlers/index-page] :route-name ::default-php]
          ["/api/fork" :get (handlers/create-world-interceptor session-atom) :route-name ::connect-world]
          ["/echo" :any [content-neg-intc handlers/echo-page] :route-name ::echo]
          ["/test" :any [content-neg-intc handlers/test-page] :route-name ::test]
          ;; Q: How should this work?
          ["/ws" :get (partial handlers/connect-renderer
                               lamport-clock
                               session-atom) :route-name ::renderer-ws]}]
    (route/expand-routes definition)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defmethod ig/init-key ::handler-map
  [_ {:keys [::debug?
             ::lamport/clock
             ::sessions/session-atom]
      :as opts}]
  (println "Setting up router. Debug mode:" debug? "\nbased on")
  (comment (pprint opts))
  ;; It seems like there's an interesting implementation issue here:
  ;; This doesn't really play nicely with route/url-for-routes.
  ;; In order to use that, the individual handlers really should
  ;; have access to the routes table.
  ;; This is a non-issue.
  ;; This is why the routing interceptor adds the :url-for key
  ;; to the context map.
  {::routes
   (if-not debug?
     (build-pedestal-routes clock session-atom)
     ;; Use this to set routes to be reloadable without a
     ;; full (reset).
     ;; Note that this will rebuild on every request, which makes it
     ;; really slow.
     (fn []
       (build-pedestal-routes clock session-atom)))})
