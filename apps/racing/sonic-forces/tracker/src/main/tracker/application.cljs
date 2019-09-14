(ns tracker.application
  (:require [com.fulcrologic.fulcro.networking.http-remote :as net]
            [com.fulcrologic.fulcro.application :as app]
            ;; Q: Is this a placeholder for planned future
            ;; expansion?
            ;; We established on Slack that Fulcro's author has the
            ;; same trouble I do with starting on an idea and getting
            ;; distracted.
            [com.fulcrologic.fulcro.components :as comp]))

(def secured-request-middleware
  "The CSRF token is embedded via server_components/html.clj"
  (->
    (net/wrap-csrf-token (or js/fulcro_network_csrf_token "TOKEN-NOT-IN-HTML!"))
    (net/wrap-fulcro-request)))

(defn specify-transit-middleware
  "Tell the server that we really need a transit response"
  [next-handler]
  (fn [{:keys [:headers]
        :as request}]
    (.log js/console "Request header keys:" (keys headers))
    (let [modified-request (if-not (contains? headers "accept")
                             (update request :headers
                                     assoc "accept" "application/transit+json")
                             request)]
      (next-handler modified-request))))

(defonce SPA (app/fulcro-app
               {;; This ensures your client can talk to a CSRF-protected server.
                ;; See middleware.clj to see how the token is embedded into the HTML
                :remotes {:remote (net/fulcro-http-remote
                                    {:url                "/api"
                                     :request-middleware (specify-transit-middleware secured-request-middleware)})}}))

(comment
  (-> SPA (::app/runtime-atom) deref ::app/indexes))
