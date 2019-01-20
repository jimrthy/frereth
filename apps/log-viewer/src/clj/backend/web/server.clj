(ns backend.web.server
  "Manage the web server Component"
  (:require [aleph.http.server :as http]
            [backend.web.routes :as routes]
            [bidi.ring :as ring]
            [integrant.core :as ig]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [redirect]]
            [ring.util.http-response :refer :all]
            [shared.lamport :as lamport]
            [renderer.sessions :as sessions]))

(defmethod ig/init-key ::web-server
  [_ {:keys [:port
             ::sessions/session-atom]
      lamport-clock ::lamport/clock
      :as opts}]
  (when-not (and lamport-clock
                 session-atom)
    (throw (ex-info "Missing something vital among"
                    opts)))
  (let [port (or port 10555)
        ;; FIXME: Switch to pedestal. It's supported aleph as a backend
        ;; since 0.5.0.
        handler (ring/make-handler (routes/build-routes lamport-clock
                                                        session-atom))
        ;; TODO: add a logger interceptor
        with-middleware (wrap-params handler)]
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
