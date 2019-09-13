(ns tracker.server-components.handlers
  ;; TODO: Move these out of middleware
  "Handle the actual requests"
  (:require
   [clojure.pprint :refer (pprint)]
   [com.fulcrologic.fulcro.server.api-middleware :as fulcro-middleware]
   [taoensso.timbre :as log]
   [tracker.server-components.pathom :as pathom]))

(defn api
  [{:keys [:body-params :headers :params :muuntaja/request :session :transit-params]
    session-key :session/key
    :as context}]
  ;; Both params and transit-params are nil.
  ;; This means parameter extraction middleware isn't working.
  (log/debug "API handler:\n\tParameter Keys:"
             (keys params)
             "\nParameters:\n"
             (with-out-str (pprint params))
             "transit-params:\n"
             (with-out-str (pprint transit-params))
             "body-params:\n"
             (with-out-str (pprint body-params))
             "headers:\n"
             (with-out-str (pprint headers))
             "for session: "
             session-key
             "\ncontaining\n"
             (with-out-str (pprint session))
             "based on context-keys:\n"
             (keys context))
  (try
    ;; handle-api-request calls the fn on body-params.
    ;; On success, it calls (fulcro-middleware/apply-response-augmentations)
    ;; to call augmentation functions that were added by calls to
    ;; fulcro-middleware/augment-response.
    ;; It sets up a {:status 200} response map with the
    ;; parse-result as the body and the augmentations merged in.
    ;; *Then* it calls fulcro-middleware/generate-response on it.
    ;; (generate-response) ensures it has both status and body
    ;; and at least the "application/transit+json" "Content-Type"
    ;; header.
    (let [response (fulcro-middleware/handle-api-request body-params
                                                         ;; This feels overly convoluted, since
                                                         ;; tx is just the transit-params.
                                                         ;; But it does add some useful functionality,
                                                         ;; so don't mess with it.
                                                         (fn [tx]
                                                           (pathom/parser request tx)))]
      (log/debug "API handler response:\n"
                 (with-out-str (pprint response)))
      response)
    (catch Exception ex
      (log/error ex "API handler failed")
      ;; Returning a 500 error indicates a pretty serious bug.
      {:status 500
       :body "oops"})))

(defn echo
  [{:keys [:body
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
    response))
