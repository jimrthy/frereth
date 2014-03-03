(ns frewreb.router
  (:require [cemerick.austin.repls :refer [browser-connected-repl-js]]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [compojure.core :refer [GET routes]]
            [compojure.route :refer  [resources not-found]]
            [net.cgrand.enlive-html :as enlive]
            [org.httpkit.server :as httpd]))

(defn build-socket-handler
  [system]
  (let [->renderer @(:->renderer system)
        renderer->-atom (:renderer-> system)]
    (reset! renderer->-atom (async/chan))
    (let [handler (fn [req]
                    (httpd/with-channel req ws
                      (httpd/on-close ws (fn [status]
                                           (println "Channel closed: " status)
                                           (async/close! @renderer->-atom)))
                      (httpd/on-receive ws (fn [data]
                                             (println "Received: " data)
                                             (async/>!! @renderer->-atom data)))
                      (async/go
                       (loop [msg (async/<! ->renderer)]
                         (when msg
                           (println "Message going to renderer: " msg)
                           (httpd/send! ws msg)
                           (recur (async/<! ->renderer)))))))]
      handler)))

;;; We use enlive lib to add to the body of the index.html page the
;;; script tag containing the JS code which activates the bREPL
;;; connection.
(enlive/deftemplate page
  (io/resource "public/index.html")
  []
  [:body] (enlive/append
            (enlive/html [:script (browser-connected-repl-js)])))

(defn all-routes
  []
  (routes
   (GET "/" _ (page))
   (resources "/")
   (not-found "Missing")))

(defn init
  []
  {:->renderer (atom nil)
   :renderer-> (atom nil)})

(defn start
  [system]
  (reset! (:->renderer system) (async/chan))
  system)

(defn stop
  [system]
  (if-let [->renderer-atom (:->renderer system)]
    (do (async/close! @->renderer-atom)
        (reset! (:->renderer system) nil))
    (println "Warning: Missing atom for the channel going out to renderer")))
