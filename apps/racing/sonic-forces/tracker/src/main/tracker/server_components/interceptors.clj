(ns tracker.server-components.interceptors
  (:require
   [clojure.pprint :refer (pprint)]
   [taoensso.timbre :as log]))

(def logger!
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
                       (with-out-str (pprint response))
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
