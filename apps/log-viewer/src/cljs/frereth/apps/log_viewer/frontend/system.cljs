(ns frereth.apps.log-viewer.frontend.system
  "Major motivation: keep autoreloads idempotent"
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.session :as session]
            [integrant.core :as ig]
            [shared.lamport :as lamport]
            [shared.specs :as specs]
            [weasel.repl :as repl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: Define this.
;; It's whatever gets returned by repl/connect
(s/def ::repl any?)

(s/def ::state (s/keys :req [::lamport/clock
                             ::repl
                             ::session/manager]))

(s/def ::opts ::state)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)

(defonce state (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defmethod ig/init-key ::repl
  [_ opts]
  (when-not (repl/alive?)
    (println "Trying to connect REPL websocket")
    ;; FIXME: Pass in the host/port as opts
    (repl/connect "ws://localhost:9001")))

(defn configure
  "Create System definition that's ready to ig/init"
  [{:keys [::lamport/clock
           ::repl
           ::session/manager]
    :as current}]
  {::lamport/clock clock
   ::repl repl
   ::session/manager manager})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef begin
  :args (s/cat :state ::state
               :initial ::opts)
  :ret ::state)
(defn begin
  [state initial]
  (swap! state
         (fn [current]
           (ig/init
            (or current
                (configure initial))))))
