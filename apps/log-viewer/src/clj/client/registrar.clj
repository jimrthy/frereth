(ns client.registrar
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.shared.specs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;;;; FIXME: Convert this to a Component
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
  :ret :frereth/world-connector)
(defn do-register-world-creator
  "Registers a command to run on world connection

  Returns falsey if the command is already registered"
  ;; This is overly simplified.

  ;; We need a permissions/authorization system.

  ;; Obvious approach: each command includes a set of
  ;; permissions that allows a user to run it. (Like
  ;; an ACL).
  ;; TODO: Look at the way Buddy handles this.

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
  (comment
    ;; Should look something like
    (let [actual-key
          (if (authorized? session-id command-key)
            command-key
            ;; Q: Would ::not-found be better?
            ;; Actually, there are 2 angles to this.
            ;; On a Linux box, if the user can't even read the directory
            ;; where a command exists, they'd get a ::not-found.
            ;; If they can see the command but aren't allowed to run it,
            ;; they'd get a ::forbidden.
            ;; This brings up a fundamental point that frereth really
            ;; needs some sort of concept of locations similar to a file
            ;; system hierarchy in addition to a permissions system.
            ;; There's an additional point that commands get much more
            ;; interesting when you can supply arguments and connect
            ;; pipes.
            ::forbidden)]
      (get @registry actual-key)))
  @registry-1)
