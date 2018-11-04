(ns backend.web.routes
  (:require
   [aleph.http :as http]
   [bidi.bidi :as bidi]
   [bidi.ring :as ring]
   [cemerick.url :as url]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5 include-js include-css]]
   [manifold.deferred :as dfrd]
   [manifold.stream :as strm]
   [renderer.lib :as lib]
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
  (dfrd/let-flow [websocket (dfrd/catch
                                 (http/websocket-connection request)
                                 (fn [_] nil))]
    (println "websocket upgrade: '" websocket "'")
    (if websocket
      (lib/activate-session! websocket)
      non-websocket-request)))

(defn create-world
  "This is really Step 2 of a World's life cycle.

  The browser side sent us a websocket notification that it was going
  to fork a new World. That handler did whatever prep work was needed
  and sent back an encrypted cookie.

  Now the browser is requesting the code that it needs to actually do
  that World's UX.

  For the 'real thing,' this should contact the Client and get the
  appropriate code from the Server."
  [{:keys [:query-params]
    :as request}]
  (println "Received a request to fork a new World:")
  (let [{cookie "cookie"
         session-id "session-id"
         world-key "world-key"} query-params]
    (if (and cookie session-id world-key)
      (let [cookie (url/url-decode cookie)
            session-id (url/url-decode session-id)
            world-key (url/url-decode world-key)]
        (println "Decoded params:")
        (pprint {::cookie cookie
                 ::session-id session-id
                 ::world-key world-key})
        (throw (RuntimeException. "Keep going")))
      (rsp/response {:status 400
                     :headers {"content-type" "application/text"}
                     :body "Missing required parameter"}))))

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
                  "api/" {"fork" (-> create-world (bidi/tag ::connect-world))}
                  "echo" (-> echo-page (bidi/tag ::echo))
                  "test" (-> test-page (bidi/tag ::test))
                  "ws" (-> connect-renderer (bidi/tag ::renderer-ws))}])
(comment
  (bidi/match-route routes "/"))
