(ns backend.web.server
  "This really should be renamed to backend.web.server"
  (:require [bidi.ring :as ring]
            [integrant.core :as ig]
            [ring.util.response :refer [redirect]]
            [ring.util.http-response :refer :all]
            [aleph.http.server :as http]
            [backend.web.routes :as routes]))

(defmethod ig/init-key ::web-server
  [_ {:keys [:port]
      :as opts}]
  (let [port (or port 10555)
        handler (ring/make-handler routes/routes)]
    (println "Starting web server on http://localhost:" port "from" opts)
    (http/start-server handler {:port port})))

(defmethod ig/halt-key! ::web-server
  [_ server]
  ;; FIXME: Also need to close the web socket connections currently
  ;; owned by routes.
  ;; Although they don't belong in there, and definitely should not be
  ;; be controlled in here.
  ;; So there are a couple of missing Components.
  (when server
    (.close server)))
