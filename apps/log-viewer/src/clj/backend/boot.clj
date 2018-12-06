(ns backend.boot
  {:boot/export-tasks true}
  (:require [boot.core :as b]
            [integrant.repl :refer [go halt]]
            [backend.main :refer :all]
            [clojure.tools.namespace.repl :refer [disable-reload!]]))

(disable-reload!)

;; This pulls in too many dependencies. It forces Docker to reload all
;; the libraries when backend code changes.
;; And it's annoying.
;; It *is* nice to have a function to use as the standard Docker CMD to
;; just run it.
;; But we don't really gain anything from this in terms of REPL startup,
;; and it causes problems when there are issues with the startup code.
;; TODO: Quit using it.
;; And decide whether it should just go away.
(b/deftask start-app
  [p port   PORT int  "Port"]
  (println "Trying to start the app")
  (let [x (atom nil)]  ; Q: What's the point?
    (b/cleanup (halt))
    (b/with-pre-wrap fileset
      (swap! x (fn [x]
                  (or x
                    (do (setup-app! {:port port})
                        (go)))))
      fileset)))
