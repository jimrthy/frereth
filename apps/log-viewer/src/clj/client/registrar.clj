(ns client.registrar
  (:require [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; TODO: Switch to this
(defonce registry (atom {}))

;; Baby-step version. Only allow a single app
(defonce registry-1 nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef do-register-world
  :args (s/cat :command-key :frereth/command
               :ctor :frereth/world-constructor)
  :ret boolean?)
(defn do-register-world
  "Returns falsey if the command is already registered"
  ;; This is overly simplified.
  ;; At the very least, need a permissions system.
  ;; Obvious approach: each command includes a set of
  ;; permissions that allows a user to run it.
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
