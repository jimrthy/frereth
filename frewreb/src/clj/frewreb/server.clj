(ns frewreb.server
  (:require [org.httpkit.server :as httpd]))

;;; To run the jetty server. The server symbol is not private to
;;; allows to start and stop thejetty server from the repl.

(defn start-web
  "Returns the stop function"
  ([app]
     (start-web 8090 app))
  ([port app]
     (start-web port app "127.0.0.1" 4))
  ([port app address threads]
     (println "Starting the web server listening at " address ":" port " with " threads " threads
And using " app " to handle responses")
     (httpd/run-server app {:port port
                            :ip address
                            :thread threads})))
