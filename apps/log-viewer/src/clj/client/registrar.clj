(ns client.registrar
  (:require [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; Called to disconnect renderer from a running world
(s/def :frereth/disconnect! (s/fspec :args nil :ret any?))

;; Not quite `any?`.
;; But anything that can be serialized to transit.
;; TODO: Find a spec for this
(s/def :frereth/message any?)

(s/def :frereth/message-sender!
  (s/fspec :args (s/cat :message :frereth/message)
           :ret any?))

(s/def :frereth/renderer->client (s/keys :req [:frereth/disconnect!
                                               :frereth/message-sender!]))

(s/def :frereth/world-stop-signal (s/or :symbol symbol?
                                        :uuid uuid?
                                        :int integer?))
(s/def :frereth/world-connector
  (s/fspec :args (s/cat :world-stop-signal :frereth/world-stop-signal
                        :send-message! :frereth/message-sender!)
           :ret :frereth/renderer->client))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; TODO: Switch to this
(defonce registry (atom {}))

;; Baby-step version. Only allow a single app
(defonce registry-1 (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef do-register-world
  ;; FIXME: All things considered, this also needs to register a
  ;; function for acquiring the source code.
  :args (s/cat :command-key :frereth/command
               :ctor :frereth/world-connector)
  :ret boolean?)
(defn do-register-world
  "Returns falsey if the command is already registered"
  ;; This is overly simplified.
  ;; At the very least, need a permissions system.
  ;; Obvious approach: each command includes a set of
  ;; permissions that allows a user to run it.

  ;; There's another problem: there are really 2
  ;; pieces to this.
  ;; 1 is the UX, which currently runs in the browser
  ;; and could very well include lots of files for
  ;; it to download.
  ;; The other should really run on the web server
  ;; side.
  ;; This gets tricky, because a native desktop
  ;; renderer still seems desirable, and that's its
  ;; own kettle of fish. I don't think I *want* it
  ;; to be like what happens in the browser.
  ;; Or maybe I do.
  ;; That approach should help keep things portable.
  ;; Maybe the "native" app is really electron, and
  ;; it includes a react-native piece for running on
  ;; mobile.
  ;; At the very least, need options here for versioning
  ;; and platform. There's a lot of TBD.
  [command-key ctor]
  (comment
    ;; This is closer to the way things should work
    (when-not (contains? registry command-key)
      (swap! registry assoc command-key ctor)))
  (reset! registry-1 ctor))

(defn lookup
  "Return World constructor for command-key if session-id can run it"
  [session-id command-key]
  @registry-1)
