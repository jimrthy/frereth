(ns backend.web.routes
  (:require
   [aleph.http :as http]
   [bidi.bidi :as bidi]
   [bidi.ring :as ring]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5 include-js include-css]]
   [manifold.deferred :as dfrd]
   [ring.util.response :as rsp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Handlers
;;;; These really belong in their own ns[es]

(def non-websocket-request
  (rsp/response {:status 400
                 :headers {"content-type" "application/text"}
                 :body "Expected a websocket request"}))

(defn connect-renderer
  [request]
  (try
    (println "connect-renderer Received keys\n"
             (keys request)
             "\namong")
    (catch Exception ex
      (println ex)))
  (pprint request)
  (dfrd/let-flow [conn (dfrd/catch
                           (http/websocket-connection request)
                           (fn [_] nil))]
    (if conn
      (throw (RuntimeException. "Get this written"))
      non-websocket-request)))

(defn connect-world
  "This is really Step 2 of a World's life cycle.

  Should happen after the to-be-implemented call to
  load-world transfers the renderer portion of the
  World to the browser.

  Both that and the Client-side need to come from
  the Server. That seems like it should also be part
  of load-world."
  [request]
  ;; It seems like this should open a new WebSocket.
  ;; There might be scenarios where it makes sense for
  ;; the isolated Workers to have their own WebSocket
  ;; connections elsewhere, but, for starters at least,
  ;; funnel all the data for all the Worlds involved
  ;; in a single web page over a single WebSocket.

  ;; What *does* make sense is for
  ;; a) World opens on a Renderer
  ;; b) That World sends back a notification that it's ready
  ;; c) Start routing messages between Client/Browser portions
  (throw (RuntimeException. "Does this make any sense at all?")))

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
      ;; At one point, it looked like the request gets html-encoded automatically.
      ;; I have some doubts about that working if I give it a hard-coded string
      ;; this way, rather than the data structure I was passing in when I saw
      ;; that.
      [:div.container [:pre [:code "" (with-out-str (pprint request))]]]]))))

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
                  #{"" "index" "index.html"} (-> index-page (bidi/tag ::index))
                  "connect-world" (-> connect-world (bidi/tag ::connect-world))
                  "echo" (-> echo-page (bidi/tag ::echo))
                  "test" (-> test-page (bidi/tag ::test))
                  "ws" (-> connect-renderer (bidi/tag ::renderer-ws))}])
(comment
  (bidi/match-route routes "/"))
