(ns backend.index
  (:require
    [hiccup.core :refer [html]]
    [hiccup.page :refer [html5 include-js include-css]]
    [ring.util.response :as rsp]))

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
