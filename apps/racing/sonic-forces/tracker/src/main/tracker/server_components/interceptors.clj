(ns tracker.server-components.interceptors
  "Anything that's legit middleware belongs in here"
  (:require
   [clojure.pprint :refer (pprint)]
   [taoensso.timbre :as log]))

(def logger!
  ;; This really should be a function that returns the interceptors
  ;; as a lexical closure with something like a UUID.
  ;; I've already hit a point where I wanted this at several different
  ;; layers. Couldn't do that because the ::internal-id would
  ;; conflict
  {:name ::logger!
   :enter (fn [{:keys [:request]
                :as ctx}]
            (let [log-ctx-id (java.util.UUID/randomUUID)]
              (log/debug "Incoming request ("
                         log-ctx-id
                         ")\n"
                         (:request-method request) " " (:uri request) "\n"
                         #_(with-out-str (pprint request))
                         (keys request))
              (assoc ctx ::internal-id log-ctx-id)))
   :leave (fn [{:keys [:response
                       ::internal-id]
                :as ctx}]
            (log/debug "Outgoing response ("
                       internal-id
                       ")\n"
                       #_(with-out-str (pprint response))
                       (if-let [{:keys [:body]} response]
                         (str "Body Type:" (type body))
                         "Body: nil"))
            (dissoc ctx ::internal-id))
   :error (fn [{:keys [:io.pedestal.interceptor.chain/error
                       ::internal-id]
                :as ctx}]
            (log/error error
                       "Log Context: "
                       internal-id)
            ctx)})
