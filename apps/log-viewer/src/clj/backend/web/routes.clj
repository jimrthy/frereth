(ns backend.routes
  (:require
   [bidi.bidi :as bidi]
   [bidi.ring :as ring]
   [clojure.java.io :as io]
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5 include-js include-css]]
   [ring.util.response :as rsp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn echo-page
  [request]
  (rsp/response
   (html
    (html5
     [:head
      [:title "Log Viewer Echo"]
      [:meta {:charset "utf-8"}]
      [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      (include-css "css/main.css")]
     [:body
      ;; FIXME: Need to html-encode the request
      [:div.container [:pre [:code request]]]]))))

(defn index-page
  [_]
  (rsp/response
   (html
    (html5
     [:head
      [:title "Log Viewer"]
      [:meta {:charset "utf-8"}]
      [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      (include-css "css/main.css")]
     [:body
      [:div.container [:div#app.app-wrapper]]
      (include-js "js/main.js")]))))

(defn test-page
  [_]
  (rsp/response
   (html
    (html5
     [:head
      [:title "Saapas tests"]]
     [:body
      [:div.container [:div#app.app-wrapper]]
      (include-js "js/test.js")]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

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
                  #{""  "index.html"} (-> index-page (bidi/tag ::index))
                  "echo" (-> echo-page (bidi/tag ::echo))
                  "test" (-> test-page (bidi/tag ::test))}])
(comment
  (bidi/match-route routes "/"))
