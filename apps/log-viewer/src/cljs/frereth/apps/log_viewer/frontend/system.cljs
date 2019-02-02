(ns frereth.apps.log-viewer.frontend.system
  "Major motivation: keep autoreloads idempotent"
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.session :as session]
            [frereth.apps.log-viewer.frontend.socket :as web-socket]
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
(s/def ::state-atom (s/and #(= (type %) Atom)
                           #(s/valid? ::state (deref %))))

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
           ::session/manager
           ::repl
           ::web-socket/sock]
    :as current}]
  {::lamport/clock clock
   ::session/manager (into {::web-socket/sock (ig/ref ::web-socket/sock)}
                           manager)
   ::repl repl
   ::web-socket/sock sock})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef begin!
  :args (s/cat :state ::state-atom
               :initial ::opts)
  :ret ::state)
(defn begin!
  [state-atom initial]
  (swap! state
         (fn [current]
           (ig/init
            (or current
                (configure initial))))))
