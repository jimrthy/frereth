(ns frereth.apps.log-viewer.frontend.core
  (:require
   [cemerick.url :as url]
   [frereth.apps.log-viewer.frontend.system :as sys]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.socket :as socket]))

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
;;;; Public

;; The js/window check protects against trying to do this
;; inside the web worker.
;; The weirdness really stems from the way I've bundled the
;; two together.
;; TODO: Move the web worker code somewhere totally different.
;; Think of this part as the terminal you're using to interact
;; with an OS while the worker is the UI portion of some app
;; that got installed on it.
(when js/window
  (let [location (.-location js/window)
        origin-protocol (.-protocol location)
        protocol-length (count origin-protocol)
        ;; https: vs. http:
        protocol (if (= \s
                        (clojure.string/lower-case (aget origin-protocol
                                                         (- protocol-length 2))))
                   "wss:"
                   "ws:")
        local-base-suffix (str "//" (.-host location))
        base-url (url/url (str origin-protocol local-base-suffix))
        ws-path (str protocol local-base-suffix  "/ws")
        ws-url (url/url ws-path)
        ;; FIXME: The path-to-fork is something that should
        ;; come back as part of the login handshake.
        ;; It's the equivalent of where to find the user's
        ;; login shell
        manager-config {::session/path-to-fork "/api/fork"
                        :frereth/session-id session-id-from-server}]
    (console.log "Configuring system, starting with ws-url:" ws-url)
    ;; FIXME: This can't *really* happen until after login.
    ;; That's when we have a session-id and know what "post-login-shell"
    ;; to "fork."
    ;; Life gets more complex on a disconnect/reconnect.
    (sys/do-begin sys/state
                  {::session/manager manager-config
                   ::socket/wrapper {::socket/base-url base-url
                                     ::socket/ws-url ws-url}})))
