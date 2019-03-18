(ns backend.web.routes
  (:require
   [backend.web.handlers :as handlers]
   [cheshire.core :as json]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [integrant.core :as ig]
   [io.pedestal.http.content-negotiation :as conneg]
   [io.pedestal.http.route :as route]
   [renderer.sessions :as sessions])
  (:import [clojure.lang ExceptionInfo IFn]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; TODO: Surely I can come up with something more restrictive
;; Actual spec:
;; Starts with a "/"
;; Consists of 0 or mor path segments separated by slashes
;; Each path segment is one of:
;; * string literal
;; * colon (\:) followed by a legal clojure identifier name.
;;   This is a path parameter (highly discouraged)
;; * asterisk (\*) followed by a legal clojure identifier name.
;;   This is a wildcard path
(s/def ::path string?)

;; Q: Restrict to legal HTTP verbs?
;; Pretty much anything is legal here now, but this really is all
;; about the HTTP routes
(s/def ::verb #_ #{:any :get :put :post :delete :patch :options :head} keyword?)

(s/def ::interceptor-view (s/or :name symbol?
                                :function #(instance? IFn %)))
(s/def ::interceptors (s/or :chain (s/coll-of ::interceptor-view)
                            :handler symbol?
                            ;; Var fits here.
                            ;; I'm not sure how clojure.lang.Var fits.
                            ;; I've tried calling instance? on various
                            ;; things that seem like they should be,
                            ;; but they all returned false.
                            :var any?
                            :map map?
                            :list list?))

(s/def ::route-name keyword?)

(s/def ::constraints map?)

;;; Basic idea is [path verb interceptors (optional route-name-clause) (optional constraints)]
(s/def ::route (s/or :base (s/tuple ::path ::verb ::interceptors)
                     :named (s/tuple ::path ::verb ::interceptors #(= :route-name %) ::route-name)
                     :constrained (s/tuple ::path ::verb ::interceptors #(= :constraints %) ::constraints)
                     :named&constrained (s/tuple ::path ::verb ::interceptors #(= :route-name %) ::route-name #(= :constraints %) ::constraints)))
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

(defn accepted-type
  [{:keys [:request]
    :as context}]
  (println "Looking for accepted response type among" request)
  (get-in request [:accept :field] "text/plain"))

(defn transform-content
  "Convert content to a string, based on type"
  [body content-type]
  (case content-type
    "application/edn" (pr-str body)
    "application/json" (json/generate-string body)
    ;; Q: What about transit?
    ;; Default to no coercion
    body))

(defn coerce-to
  "Coerce the response body and Content-Type header"
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-content-type
  "If no content-type header, try to deduce one and transform body"
  {:name ::coerce-content-type
   :leave (fn [context]
            (cond-> context
              (nil? (get-in context [:response :headers "Content-Type"]))
              (update-in [:response] coerce-to (accepted-type context))))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal

(s/fdef build-pedestal-routes
  :args (s/cat :lamport-clock ::lamport/clock
               :session-atom ::sessions/session-atom)
  :ret ::route-set)
(defn build-pedestal-routes
  [lamport-clock
   session-atom]
  (let [content-neg-intc (conneg/negotiate-content ["text/html" "application/edn" "text/plain"])
        default-intc [coerce-content-type content-neg-intc]
        definition
        #{["/" :get (conj default-intc handlers/index-page) :route-name ::default]
          ["/index" :get (conj default-intc handlers/index-page) :route-name ::default-index]
          ["/index.html" :get (conj default-intc handlers/index-page) :route-name ::default-html]
          ["/index.php" :get (conj default-intc handlers/index-page) :route-name ::default-php]
          ["/api/fork" :get
           (conj default-intc
                 (handlers/create-world-interceptor session-atom))
           :route-name ::connect-world]
          ["/echo" :any (conj default-intc handlers/echo-page) :route-name ::echo]
          ["/test" :any (conj default-intc handlers/test-page) :route-name ::test]
          ["/ws" :get (handlers/build-renderer-connection
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
     ;; Note that this will rebuild on every request, which slows it
     ;; down.
     (fn []
       (build-pedestal-routes clock session-atom)))})
