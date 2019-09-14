(ns tracker.server-components.handlers
  ;; TODO: Move the rest out of middleware
  "Handle the actual requests"
  (:require
   [clojure.data :as data]
   [clojure.pprint :refer (pprint)]
   [com.fulcrologic.fulcro.server.api-middleware :as fulcro-middleware]
   [taoensso.timbre :as log]
   [tracker.server-components.pathom :as pathom]))

(defn api
  [{:keys [:body-params :headers :params :muuntaja/request :session :transit-params]
    session-key :session/key
    :as context}]
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
    ;; There's a bunch of copy/paste from fulcro-middleware/wrap-api.
    ;; I want most of what it does, but the call to generate-response
    ;; breaks the content-negotiation interceptor.
    (let [parse-result (pathom/parser request body-params)]
      (log/debug "Parser returned:\n"
                 (with-out-str (pprint parse-result)))
      (if (instance? Throwable parse-result)
        ;; Note that this really is a big deal: we should not get
        ;; an exception back here, and Fulcro isn't going to handle
        ;; this gracefully.
        ;; Q: What's a good way to signal an error toast?
        {:status 500 :body "Internal server error. Parser threw an exception. See server logs for details."}
        (let [augmented (fulcro-middleware/apply-response-augmentations parse-result)]
          ;; The augmentation isn't doing anything.
          ;; Q: Should it?
          (log/debug "Augmentation:" (into []
                                           (data/diff parse-result augmented)))
          (let [response (merge {:status 200 :body parse-result}
                                augmented)]
            (log/debug "API handler response:\n"
                       (with-out-str (pprint response)))
            response))))
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
    response))
