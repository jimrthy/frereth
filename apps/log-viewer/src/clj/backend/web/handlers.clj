(ns backend.web.handlers
  (:require
   [aleph.http :as http]
   [cemerick.url :as url]
   [clojure.core.async :as async]
   [clojure.edn :as edn]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.connection :as connection]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   ;; Initially, at least, this is for ::async/chan
   [frereth.apps.shared.specs :as specs]
   [frereth.cp.shared.util :as cp-util]
   [frereth.weald.logging :as log]
   [frereth.weald.specs :as weald]
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5 include-js include-css]]
   [io.pedestal.interceptor.chain :as interceptor-chain]
   [manifold.deferred :as dfrd]
   [renderer.lib :as lib]
   [renderer.sessions :as sessions]
   [ring.util.response :as rsp])
  (:import clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: Most of these should honestly be in a Pedestal ns
;; And there's some definite overlap with the backend.web.service ns

;; FIXME: Specify which keys are expected for Request and Response
(s/def ::request map?)
(s/def ::response map?)
(s/def ::interceptor-chain/error #(instance? ExceptionInfo %))

(s/def ::context (s/keys :req-un [::request]
                         :opt-un [::interceptor-chain/error
                                  ::response]))

(s/def ::possibly-deferred-context (s/or :context ::context
                                         :deferred ::async/chan))

(s/def ::enter (s/fspec :args (s/cat :context ::context)
                        :ret ::possibly-deferred-context))

(s/def ::leave (s/fspec :args (s/cat :context ::context)
                        :ret ::possibly-deferred-context))

(s/def ::error (s/fspec :args (s/cat :context ::context
                                     :exception ::interceptor-chain/error)
                        :ret ::context))

;; Q: What's actually allowed here?
(s/def ::name keyword?)

;; Actually, this is a dict that's suitable for converting into an
;; interceptor.
(s/def ::interceptor (s/keys :opt-un [::enter ::error ::leave ::name]))

;;; I know I've written a spec for this at some point or other.
;;; FIXME: Track that down so I don't have to duplicate the effort.
(s/def ::ring-request map?)

(s/def ::connection-specs (s/keys :req [::lamport/clock
                                        ::sessions/session-atom
                                        ::weald/logger
                                        ::weald/state-atom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Helpers

(def non-websocket-request
  "For requests at the /ws endpoint missing the upgrade header"
  (rsp/bad-request "Expected a websocket request"))

(s/fdef connect-web-socket-to-session!
  :args (s/cat :component ::connection-specs
               :deferred-socket dfrd/deferred?)
  ;; Originally, this returned an async/go-block
  ;; Bypassing that particular abstraction doesn't seem to make any
  ;; difference, and that original approach seemed weird.
  ;; Q: Are we missing anything by handling it this way instead?
  :ret dfrd/deferred?)
(defn connect-web-socket-to-session!
  [{:keys [::weald/logger]
    log-state-atom ::weald/state-atom
    :as component}
   dfrd-sock]
  ;; The person who's probably taking over as
  ;; the maintainer for aleph strongly dislikes
  ;; let-flow.
  ;; That seems like a sign that it's probably
  ;; worth avoiding it.
  ;; So...what's the "proper" way to do this?
  (dfrd/let-flow [websocket (dfrd/catch
                                dfrd-sock
                                (fn [_] nil))]
                 (swap! log-state-atom
                        #(log/flush-logs! logger
                                          (log/info % ::connect-web-socket-to-session!
                                                    "websocket upgrade"
                                                    {::result websocket})))
                 (if websocket
                   (do
                     (swap! log-state-atom
                            #(log/flush-logs! logger
                                              (log/info % ::connect-web-socket-to-session!
                                                        "Built a websocket")))
                     (try
                       (lib/activate-session! (assoc component
                                                     ::connection/web-socket websocket))
                       websocket
                       (catch Exception ex
                         (swap! log-state-atom
                                #(log/flush-logs! logger
                                                  (log/exception % ex ::connect-web-socket-to-session!))))))
                   (do
                     (swap! log-state-atom
                            #(log/flush-logs! logger
                                              (log/warn % ::connect-web-socket-to-session!
                                                        "No websocket")))
                     non-websocket-request))))

(s/fdef safely-register-new-world!
  :args (s/cat :log-state-atom ::weald/state-atom
               :session-atom ::sessions/session-atom
               :cookie bytes?
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::response)
(defn safely-register-new-world!
  ;; This really conflates 2 completely unrelated pieces.
  ;; But it's the product of refactoring a real mess.
  ;; Actually, it highlights that mess.
  "1. Registers that a browser is legitimately trying to fork a new world
  2. wraps up the response body nicely"
  [log-state-atom
   session-atom
   cookie
   session-id
   world-key]
  (try
    (lib/register-pending-world! session-atom
                                 session-id
                                 world-key
                                 cookie)
    (swap! log-state-atom
           #(log/info %
                      ::build-world-code
                      "Registration succeeded. Should be good to go"))
    (catch Throwable ex
      ;; This catch/log/rethrow seems silly, but the bug that
      ;; escaped without it was a nightmare to track down
      (swap! log-state-atom
             #(log/exception %
                             ex
                             ::build-world-code
                             "Registration failed"))
      (throw ex))))

(s/fdef build-world-code
  :args (s/cat :log-state-atom ::weald/state-atom
               :session-atom ::sessions/session-atom
               :signature bytes?
               :cookie bytes?
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::response)
(defn build-world-code
  [log-state-atom
   session-atom
   signature
   cookie
   session-id
   world-key]
  (swap! log-state-atom
         #(log/debug %
                     ::build-world-code
                     "Trying to decode cookie"
                     {:frereth/cookie cookie
                      ::cookie-type (type cookie)}))
  (let [session-id (-> session-id
                       url/url-decode
                       ;; Q: Would transit make more sense than EDN for these?
                       edn/read-string)
        world-key (-> world-key
                      url/url-decode
                      edn/read-string)]
    (swap! log-state-atom
           #(log/debug %
                       ::build-world-code
                       "Decoded params:"
                       {::cookie cookie
                        ::session-id session-id
                        ::world-key world-key
                        ::signature signature}))
    (try
      (if-let [body (lib/get-code-for-world log-state-atom
                                            @session-atom
                                            session-id
                                            world-key
                                            cookie)]
        (do
          (swap! log-state-atom
                 #(log/trace %
                             ::build-world-code
                             "World code retrieved"
                             {:response/body body}))
          (safely-register-new-world! log-state-atom session-atom cookie session-id world-key)
          ;; Q: Should we be friendlier and allow content negotiation
          ;; here?
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
                            ;; else).
                            "application/ecmascript"))
        (do
          (swap! log-state-atom
                 #(log/error %
                             ::build-world-code
                             "Missing"
                             {:frereth/session-id session-id
                              :frereth/world-key world-key}))
          (rsp/not-found "Unknown World")))
      (catch ExceptionInfo ex
        (swap! log-state-atom
               #(log/exception %
                               ex
                               ::build-world-code
                               "Error retrieving code for World"))
        (rsp/bad-request "Malformed Request"))
      (catch Exception ex
        ;; This seems silly...but what would
        ;; be more appropriate?
        (swap! log-state-atom
               #(log/exception %
                               ex
                               ::build-world-code
                               "Exception escaping") )
        (throw ex)))))

(s/fdef pending-world-interceptor
  :args (s/cat :logger ::weald/logger
               :log-state-atom ::weald/state-atom
               :session-atom ::sessions/session-atom
               :context ::context)
  :ret ::context)
(defn pending-world-interceptor
  "Browser signaled that it wants to fork/join a new world"
  [logger log-state-atom session-atom
   {{:keys [:query-params]
       :as request} :request
      :as context}]
  (try
    (swap! log-state-atom
           #(log/trace %
                       ::create-world-interceptor
                       "Received a request for code to fork a new World"))
    ;; These parameters need to be serialized into a signed "initiate"
    ;; param.
    (let [{initiate-wrapper :initiate
           :keys [:signature]} query-params]
      ;; It's tempting to think that we don't need/want the
      ;; Cookie here.
      ;; But we don't want some rogue World just randomly
      ;; sending requests to try to interfere with legitimate
      ;; ones.
      ;; So the Cookie *is* needed to validate the Session
      ;; and World keys.
      (if (and initiate-wrapper signature)
        (let [{:keys [:frereth/cookie
                      :frereth/session-id
                      :frereth/world-key]
               :as initiate} (serial/deserialize initiate-wrapper)]
          (if (and cookie session-id world-key)
            (assoc context :response
                   (build-world-code log-state-atom session-atom signature cookie session-id world-key))
            (do
              (swap! log-state-atom
                     #(log/error %
                                 ::create-world-interceptor
                                 "Missing piece of initiate"
                                 initiate))
              (assoc context :response
                     (rsp/bad-request "Missing required parameter")))))
        (do
          (swap! log-state-atom
                 #(log/error %
                             ::create-world-interceptor
                             "Missing required query param"
                             query-params))
          (assoc context :response
                 (rsp/bad-request "Missing required parameter")))))
    (catch Throwable ex
      (swap! log-state-atom
             #(log/exception %
                             ex
                             ::create-world-interceptor
                             "Unhandled low-level outer exception:"))
      (throw ex))
    (finally
      (swap! log-state-atom
             #(log/flush-logs! logger %)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Handlers

(s/fdef build-renderer-connection
  :args (s/cat :connection-specs ::connection-specs)
  :ret ::interceptor)
(defn build-renderer-connection
  [{:keys [::sessions/session-atom
           ::weald/logger]
    lamport-clock ::lamport/clock
    log-state-atom ::weald/state-atom
    :as component}]
  {:name ::connect-renderer!
   :enter (fn [{:keys [:request]
                :as ctx}]
            (when-not log-state-atom
              (println "WARNING: Missing ::weald/state-atom among"
                       (keys component)
                       "\nin\n"
                       (cp-util/pretty component)))
            (try
              (swap! log-state-atom
                     #(log/flush-logs! logger
                                       (log/info % ::connect-renderer!
                                                 "Incoming renderer connection request"
                                                 request)))
              (let [dfrd-sock (http/websocket-connection request)]
                (assoc ctx
                       ;; Having the response body return a go block that, in turn,
                       ;; derefs as a manifold.Deferred seems weird, at best.
                       ;; But it seems to work.
                       :response {:body (connect-web-socket-to-session! component dfrd-sock)}))
              (catch Exception ex
                (swap! log-state-atom #(log/exception % ex ::connect-renderer)))
              (finally
                (swap! log-state-atom
                       #(log/flush-logs! logger %)))))})

(s/fdef create-world-interceptor
  :args (s/cat :log-state-atom ::weald/state-atom
               :session-atom ::sessions/session-atom)
  :ret ::interceptor)
;; FIXME: This name seems misleading.
;; It seems like connect-to-possibly-new-world might be more
;; accurate.
;; Then again...there's a drastic difference between plugging
;; into an existing, possibly multi-player world, and forking
;; a new instance.
;; OTOH, interceptor-for-forking-or-joining-world is a
;; ridiculous name.
;; Q: Does it make sense to split this into 2?
;; 1 could handle creation, the other could handle joining.
;; The ideas are so similar that it seems ridiculous at face
;; value.
;; That doesn't mean that it isn't worth contemplating.
;; Because, after all, they're totally distinct.
(defn create-world-interceptor
  "This is really Step 2 of a World's life cycle.

  The browser side sent us a websocket notification that it was going
  to \"fork\" (or attach to) a new World. That handler did whatever prep
  work was needed and sent back an encrypted cookie.

  Now the browser is requesting the code that it needs to actually do
  that World's UX.

  For the 'real thing,' this should contact the Client and get the
  appropriate code from the Server."
  [logger log-state-atom session-atom]
  {:name ::world-forker
   :enter (partial pending-world-interceptor logger log-state-atom session-atom)})

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
      ;; OTOH, the pretty-print formatting is nice.
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
