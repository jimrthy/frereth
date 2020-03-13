(ns frereth.apps.roadblocks.core
  "This namespace contains your application and is the entrypoint for 'yarn start'."
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.roadblocks.system :as sys]
   [frereth.apps.roadblocks.window-manager :as w-m]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.socket :as socket]
   [frereth.apps.shared.window-manager :as shared-wm]
   [frereth.apps.shared.worker :as worker]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)

;; FIXME: Don't hard-code this. It should really be something like
;; an encrypted JWT that we received at login
(def session-id-from-server
  "Need something we can use for authentication and validation

  There are multiple levels to this.

  There's one for the SESSION (this).

  And also a pair for each World instance"
  [-39 -55 106 103
   -31 117 120 57
   -102 12 -102 -36
   32 77 -66 -74
   97 29 9 16
   12 -79 -102 -96
   89 87 -73 116
   66 43 39 -61])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Helpers

(s/fdef build-socket-wrapper-config
  :args (s/cat :window-location #(= (type %) js/window.Location))
  :ret ::socket/wrapper)
(defn build-socket-wrapper-config
  "Define how to get to the websocket that will do most of the work"
  [location]
  (let [origin-protocol (.-protocol location)
        protocol-length (count origin-protocol)
        ;; https: vs. http:
        protocol (if (= \s
                        (clojure.string/lower-case (aget origin-protocol
                                                         (- protocol-length 2))))
                   "wss:"
                   "ws:")
        local-base-suffix (str "//" (.-host location))
        base-url (str origin-protocol local-base-suffix)
        ws-url (str protocol local-base-suffix  "/ws")]
    {::socket/base-url base-url
     ::socket/ws-url ws-url}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

;; The :after-load metadata seems to inject this into the shadow-cljs
;; lifecycle.
;; That may or may not make sense.
(defn ^:dev/after-load ^:export main  ; Q: Does the ^:export make sense under shadow-cljs?
  "Run application startup logic"
  []
  (assert js/window)  ; leftover from mixing everything up w/ Web Worker
  (let [socket-wrapper (build-socket-wrapper-config (.-location js/window))
        initial-path "/api/attract"  ; start in attract/demo mode
        manager-config {::session/path-to-fork-shell initial-path
                        ;; FIXME: This should be hidden inside a cookie
                        :frereth/session-id session-id-from-server}
        base-wm-cfg {::shared-wm/ctor w-m/do-start
                     ::shared-wm/dtor! (fn []
                                         (.warn js/console "Send shut-down notifications"))}]
    (.log js/console "Configuring system, starting with socket-wrapper:"
          (clj->js socket-wrapper))
    ;; Contrary to comments in the original log-viewer, want to start
    ;; with an initial anonymous session.
    ;; The obvious way to handle this is to inject the session-id
    ;; into the initial html.
    ;; Q: How badly does that go wrong?
    ;; A: It goes into a header. Duh.
    (try
      (sys/do-begin sys/state
                    {::session/manager manager-config
                     ::socket/wrapper socket-wrapper
                     ::shared-wm/base base-wm-cfg})
      (catch :default ex
        (.error js/console "Oops" ex)))))
