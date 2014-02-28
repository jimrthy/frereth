(ns frewreb.router
  (:require [cemerick.austin.repls :refer [browser-connected-repl-js]]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [compojure.core :refer [GET defroutes]]
            [compojure.handler :refer [site]]
            [compojure.route :refer  [resources not-found]]
            [net.cgrand.enlive-html :as enlive]
            [org.httpkit.server :as httpd]))

(defn build-socket-router
  "socket-channel-atom: an atom that will be reset to a web channel that can be
used to send! messages to the renderer
renderer-> something like a core.async channel that will receive messages from
the renderer."
  [socket-channel-atom renderer->]
  (let [router (fn [req]
                 (httpd/with-channel req channel
                   (httpd/on-close channel (fn [status]
                                             (println "channel closed: " status)))
                   (httpd/on-receive channel (fn [data]
                                               (async/>!! renderer-> data)))
                   (swap! socket-channel-atom (fn [_]
                                                channel))))]
    router))

;;; We use enlive lib to add to the body of the index.html page the
;;; script tag containing the JS code which activates the bREPL
;;; connection.
(enlive/deftemplate page
  (io/resource "public/index.html")
  []
  [:body] (enlive/append
            (enlive/html [:script (browser-connected-repl-js)])))

;;; defroutes macro defines a function that chains individual route
;;; functions together. The request map is passed to each function in
;;; turn, until a non-nil response is returned.
(defroutes all-routes
  (GET "/" req (page))
  (resources "/")
  (not-found "Missing"))

;;; This next function almost seems like it's not worth the effort
;;; of defining. Oh well. It keeps details like this in one spot.
(def web-router
  (site #'all-routes))

