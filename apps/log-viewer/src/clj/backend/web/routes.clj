(ns backend.web.routes
  (:require
   [aleph.http :as http]
   [bidi.bidi :as bidi]
   [bidi.ring :as ring]
   [cemerick.url :as url]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.lamport :as lamport]
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5 include-js include-css]]
   [manifold.deferred :as dfrd]
   [manifold.stream :as strm]
   [renderer.lib :as lib]
   [renderer.sessions :as sessions]
   [ring.util.response :as rsp])
  (:import clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;; I know I've written a spec for this at some point or other.
;;; FIXME: Track that down so I don't need to duplicate it.
(s/def ::ring-request map?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Helpers

(def non-websocket-request
  (rsp/bad-request "Expected a websocket request"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Handlers
;;;; These really belong in their own ns[es]

(s/fdef connect-renderer
  :args (s/cat :lamport-clock ::lamport/clock
               :session-atom ::sessions/session-atom
               :request ::ring-request)
  :ret dfrd/deferred?)
(defn connect-renderer
  [lamport-clock session-atom request]
  (try
    (println "connect-renderer: Request from\n"
             (:remote-addr request))
    (catch Exception ex
      (println ex)))
  (dfrd/let-flow [websocket (dfrd/catch
                                 (http/websocket-connection request)
                                 (fn [_] nil))]
    (println "connect-renderer: websocket upgrade: '" websocket "'")
    (if websocket
      (lib/activate-session! lamport-clock session-atom websocket)
      non-websocket-request)))

(s/fdef create-world
  ;; FIXME: Yet again, need specs for ring request/response
  :args (s/cat :sessionatom ::sessions/session-atom
               :ring-request any?)
  :ret any?)
(defn create-world
  ;; FIXME: This name seems misleading.
  ;; It seems like connect-to-possibly-new-world might be more
  ;; accurate.
  ;; Then again...there's a drastic difference between plugging
  ;; into an existing, possibly multi-player world, and forking
  ;; a new instance.
  "This is really Step 2 of a World's life cycle.

  The browser side sent us a websocket notification that it was going
  to fork a new World. That handler did whatever prep work was needed
  and sent back an encrypted cookie.

  Now the browser is requesting the code that it needs to actually do
  that World's UX.

  For the 'real thing,' this should contact the Client and get the
  appropriate code from the Server."
  [session-atom
   {:keys [:query-params]
    :as request}]
  (try
    (println ::create-world
             "Received a request for code to fork a new World:")
    ;; These parameters need to be serialized into a signed "initiate"
    ;; param.
    (let [{initiate-wrapper "initiate"
           signature "signature"} query-params
          ;; It's tempting to think that we don't need/want the
          ;; Cookie here.
          ;; But we don't want some rogue World just randomly
          ;; sending requests to try to interfere with legitimate
          ;; ones.
          ;; So the Cookie *is* needed to validate the Session
          ;; and World keys.
          {:keys [:frereth/cookie
                  :frereth/session-id
                  :frereth/world-key]
           :as initiate} (serial/deserialize initiate-wrapper)]
      (if (and cookie session-id world-key)
        (do
          (println ::create-world "Trying to decode" cookie
                   "a" (class cookie))
          (let [session-id (-> session-id
                               url/url-decode
                               edn/read-string)
                world-key (-> world-key
                              url/url-decode
                              edn/read-string)]
            (println ::create-world "Decoded params:")
            ;; There's a type mismatch between here and what the lib
            ;; expects.
            ;; These values are still JSON.
            ;; That's expecting cookie as a byte array.
            ;; session-id and world-key are supposed to be...whatever
            ;; I'm using to represent keys (the map of JWK values would
            ;; be most appropriate).
            ;; So I need to finish deserializing these.
            ;; At the same time, this should really have been sent as
            ;; a single signed blob that extracts to these values.
            (pprint {::cookie cookie
                     ::session-id session-id
                     ::world-key world-key
                     ::signature signature})
            (try
              (if-let [body (lib/get-code-for-world @session-atom
                                                    session-id
                                                    world-key
                                                    cookie)]
                (do
                  (println ::create-world "Response body:" body)
                  (try
                    (lib/register-pending-world! session-atom
                                                 session-id
                                                 world-key
                                                 cookie)
                    (println ::create-world
                             "Registration succeeded. Should be good to go")
                    (catch Throwable ex
                      (println ::create-world "Registration failed:" ex)
                      (throw ex)))
                  (rsp/content-type (rsp/response body)
                                    ;; Q: What is the response type, really?
                                    ;; It seems like it would be really nice
                                    ;; to return a script that sets up an
                                    ;; environment with its own
                                    ;; clojurescript compiler and a basic script
                                    ;; to kick off whatever the World needs to
                                    ;; do.
                                    ;; That would be extremely presumptuous and
                                    ;; wasteful, even for a project as
                                    ;; extravagant as this one.
                                    ;; (The fact that that's what I want
                                    ;; to build/use isn't justification
                                    ;; to impose that overhead on anyone
                                    ;; else.
                                    "application/ecmascript"))
                (do
                  (println ::create-world "Missing")
                  (pprint {:frereth/session-id session-id
                           :frereth/world-key world-key})
                  (rsp/not-found "Unknown World")))
              (catch ExceptionInfo ex
                (println "Error retrieving code for World\n" ex)
                (pprint (ex-data ex))
                (rsp/bad-request "Malformed Request"))
              (catch Exception ex
                (println "Unhandled exception:" ex)
                (throw ex)))))
        (do
          (println "Missing parameter in")
          (pprint initiate)
          (rsp/bad-request "Missing required parameter"))))
    (catch Throwable ex
      (println "Unhandled low-level outer exception:" ex)
      (throw ex))))

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

(s/fdef build-routes
  :args (s/cat :lamport-clock ::lamport/clock
               :session-atom ::sessions/atom)
  ;; FIXME: Track down a spec for this
  :ret any?)
(defn build-routes
  [lamport-clock
   session-atom]
  ;; Note: when running uberjar from project dir, it is
  ;; possible that the dev-output dir exists.
  ["/" [["js/" (if (.exists (io/file "dev-output/js"))
                 (ring/->Files {:dir "dev-output/js"})
                 (ring/->Resources {:prefix "js"}))]
        ["css/" (if (.exists (io/file "dev-output/css"))
                  (ring/->Files {:dir "dev-output/js"})
                  (ring/->Resources {:prefix "css"}))]
        [#{"" "index" "index.html"} (bidi/tag index-page ::index)]
        ["api/" {"fork" (bidi/tag (partial create-world
                                           session-atom) ::connect-world)}]
        ["echo" (bidi/tag echo-page ::echo)]
        ["test" (bidi/tag test-page ::test)]
        ["ws" (bidi/tag (partial connect-renderer
                                 lamport-clock
                                 session-atom) ::renderer-ws)]]
   [[true :not-found]]])
(comment
  ;; If I were going to stick with bidi, this would probably be worth
  ;; converting to a unit test
  (bidi/match-route (build-routes nil nil) "/")
  (bidi/match-route (build-routes nil nil) "/api/fork")
  (bidi/match-route (build-routes nil nil) "/://?initiate"))
