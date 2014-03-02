(ns frewreb.router
  (:require [cemerick.austin.repls :refer [browser-connected-repl-js]]
            [chord.http-kit :refer [with-channel]]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [compojure.core :refer [GET routes]]
            [compojure.route :refer  [resources not-found]]
            [net.cgrand.enlive-html :as enlive]
            [org.httpkit.server :as httpd]))

(defn socket-handler
  [req system]
  (with-channel req ws
    (println "Opened connection from " (:remote-addr req))
    (println "Request: " req)
    (async/go-loop []
     (when-let [{:keys [message error] :as msg} (async/<! ws)]
       (println "Message received: " message)
       (async/>! ws (if error
                      "Error ACK"
                      "Hello renderer from client!"))))))

;;; We use enlive lib to add to the body of the index.html page the
;;; script tag containing the JS code which activates the bREPL
;;; connection.
(enlive/deftemplate page
  (io/resource "public/index.html")
  []
  [:body] (enlive/append
            (enlive/html [:script (browser-connected-repl-js)])))



