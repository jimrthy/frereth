(ns backend.server
  (:require [bidi.bidi :as bidi]
            [bidi.ring :as ring]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [ring.util.response :refer [redirect]]
            [ring.util.http-response :refer :all]
            [aleph.http.server :as http]
            [backend.routes :as routes]))

;; TODO: Convert the routing to its own component in the system.
;; Possibly even set up the handler function to call the mapping
;; dynamically so this can reload without restarting the entire
;; system

;; Note: when running uberjar from project dir, it is
;; possible that the dev-output dir exists.
(def routes ["/" {"js/" (if (.exists (io/file "dev-output/js"))
                          (ring/->Files {:dir "dev-output/js"})
                          (ring/->Resources {:prefix "js"}))
                  "css/" (if (.exists (io/file "dev-output/css"))
                           (ring/->Files {:dir "dev-output/js"})
                           (ring/->Resources {:prefix "css"}))
                  #{""  "index.html"} (-> routes/index-page (bidi/tag ::index))
                  "echo" (-> routes/echo-page (bidi/tag ::echo))
                  "test" (-> routes/test-page (bidi/tag ::test))}])
(comment
  (bidi/match-route routes "/"))

(def handler
  (ring/make-handler routes))

(defmethod ig/init-key ::web-server
  [_ {:keys [:port]
      :as opts}]
  (let [port (or port 10555)]
    (println "Starting web server on http://localhost:" port "from" opts)
    (http/start-server handler {:port port})))

(defmethod ig/halt-key! ::web-server
  [_ server]
  (when server
    (.close server)))

(defn new-system [opts]
  {::web-server opts})
