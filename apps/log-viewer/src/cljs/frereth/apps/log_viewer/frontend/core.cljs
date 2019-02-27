(ns frereth.apps.log-viewer.frontend.core
  (:require-macros
   [cljs.core.async.macros :refer [go]]
   [frontend.macro :refer [foobar]])
  (:require
   [cemerick.url :as url]
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   ;; FIXME: This can go away with connect-web-socket!
   [clojure.string]
   ;; FIXME: This really should go away also.
   ;; Though we'll also have to break the web workers
   ;; out into their own component.
   [cognitect.transit :as transit]
   [foo.bar]
   [frereth.apps.log-viewer.frontend.session :as session]
   [frereth.apps.log-viewer.frontend.socket :as socket]
   [frereth.apps.log-viewer.frontend.system :as sys]
   ;; Start by at least partially supporting this, since it's
   ;; so popular
   [reagent.core :as r]
   [weasel.repl :as repl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: Define these
(s/def ::async-chan any?)
;; Black box the web server sent as part of its forking-ack response.
;; Right now, this is an Uint8Array.
;; I'm dubious about this decision.
(s/def ::cookie any?)

;; Instance of ArrayBuffer
(s/def ::array-buffer any?)
;; TODO: better than this
(s/def ::jwk map?)
;; Something like crypto/Key
(s/def ::crypto-key any?)
(s/def ::public-key ::crypto-key)
(s/def ::public ::public-key)
(s/def ::secret ::crypto-key)
(s/def ::internal-key-pair (s/keys :required [::public ::secret]))
;; Something like crypto/KeyPair
;; i.e. the native javascript equivalent of ::internal-key-pair
(s/def ::key-pair any?)

(s/def ::session-id any?)
;; Instance of SubtleCrypto
(s/def ::subtle-crypto any?)
(s/def ::web-socket any?)
(s/def ::web-worker any?)

;; This is some sort of human-readable/transit-serializable
;; representation of a World's public key
;; TODO: Take advantage of renderer.world
(s/def ::world-key map?)
(s/def ::world-state-keys #{::active ::forked ::forking ::pending})
(s/def ::state ::world-state-keys)
;; FIXME: The legal values here are pretty interesting.
;; And, honestly, specific to each state-key
(s/def ::world-state (s/or :pending (s/keys :req [::waiting-ack
                                                  ::state])))

(s/def work-spawner (s/fspec :args (s/cat :cookie ::cookie)
                             :ret ::web-worker))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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
        protocol (if (= \s
                        (clojure.string/lower-case (aget origin-protocol
                                                         (- protocol-length 2))))
                   "wss:"
                   "ws:")
        local-base-suffix (str "//" (.-host location))
        base-url (url/url (str origin-protocol local-base-suffix))
        ws-path (str protocol local-base-suffix  "/ws")
        ws-url (url/url ws-path)]
    (console.log "Configuring system, starting with ws-url:" ws-url)
    ;; FIXME: This can't *really* happen until after login.
    ;; That's when we have a session-id and know what "post-login-shell"
    ;; to "fork."
    ;; Life gets more complex on a disconnect/reconnect.
    (sys/do-begin sys/state
                  ;; FIXME: The path-to-fork is something that should
                  ;; come back as part of the login handshake.
                  ;; It's the equivalent of the user's login shell.
                  {::session/manager {::session/path-to-fork "/api/fork"
                                      ::session/session-id session-id-from-server}
                   ::socket/wrapper {::socket/base-url base-url
                                     ::socket/ws-url ws-url}})))

(comment
  ;; Macro test
  (foobar :abc 3)

  ;; Example of interop call to plain JS in src/cljs/foo.js
  (js/foo)

  (println "Console print check"))
