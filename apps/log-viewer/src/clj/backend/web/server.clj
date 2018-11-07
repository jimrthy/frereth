(ns backend.web.server
  "This really should be renamed to backend.web.server"
  (:require [aleph.http.server :as http]
            [backend.web.routes :as routes]
            [bidi.ring :as ring]
            [integrant.core :as ig]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [redirect]]
            [ring.util.http-response :refer :all]))

(defmethod ig/init-key ::web-server
  [_ {:keys [:port]
      :as opts}]
  (let [port (or port 10555)
        ;; FIXME: Switch to pedestal. It's supported aleph as a backend
        ;; since 0.5.0.
        handler (ring/make-handler routes/routes)
        ;; TODO: Switch to something more like Pedestal's Interceptor
        ;; model.
        ;; Q: Could I just use that part of their library?
        with-middleware (-> handler
                            wrap-params)]
    (println "Starting web server on http://localhost:" port "from" opts)
    (http/start-server with-middleware {:port port})))

(defmethod ig/halt-key! ::web-server
  [_ server]
  ;; FIXME: Also need to close the web socket connections currently
  ;; owned by routes.
  ;; Although they don't belong in there, and definitely should not be
  ;; be controlled in here.
  ;; So there are a couple of missing Components.
  (when server
    (.close server)))
