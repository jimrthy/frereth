(ns frereth.apps.log-viewer.frontend.system
  "Major motivation: keep autoreloads idempotent"
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.session :as session]
            [integrant.core :as ig]
            [shared.lamport :as lamport]
            [weasel.repl :as repl]))

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
    (repl/connect "ws://localhost:9001")))

(defn configure
  [{:keys [::lamport/clock
           ::repl
           ::session/manager]
    :as current}]
  {::lamport/clock clock
   ::repl repl
   ::session/manager manager})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn begin!
  []
  (when-not @state
    (swap! state
           (fn [current]
             (ig/init (configure current))))))
