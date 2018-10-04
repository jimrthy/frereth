(ns backend.server
  (:require [bidi.bidi :as bidi]
            [clojure.java.io :as io]
            [compojure.core :refer [GET defroutes]]
            [compojure.route :refer [resources files]]
            [compojure.handler :refer [api]]
            [integrant.core :as ig]
            [ring.util.response :refer [redirect]]
            [ring.util.http-response :refer :all]
            [aleph.http.server :refer [start-server]]
            [backend.index :refer [index-page test-page]]))

;; Note: when running uberjar from project dir, it is
;; possible that the dev-output dir exists.
(def routes ["/" ["js/" (if (.exists (io/file "dev-output/js"))
                          (bidi/->Files {:dir "dev-output/js"})
                          (bidi/->Resources {:prefix "js"}))
                  "css/" (if (.exists (io/file "dev-output/css"))
                           (bidi/->Files {:dir "dev-output/js"})
                           (bidi/->Resources {:prefix "css"}))
                  #{"index.html"} (-> index-page (bidi/tag ::index))
                  "echo" (-> echo-page (bidi/tag ::echo))
                  "test" (-> test-pag (bidi/tag ::test))
                  true (-> index-page (bidi/tag ::index))]])

(def handler
  (bidi/make-handler routes))

(defmethod ig/init-key ::web-server
  [_ {:keys [:port]
      :as opts
      :or {port 10555}}]
  (println (str "Starting web server on http://localhost:" port))
  (http/start-server handler {:port port}))

(defmethod ig/halt-key! ::web-server
  [_ server]
  (when server
    (.close server)))

(defn new-system [opts]
  {::web-server opts})
