(ns frereth.apps.log-viewer.frontend.system
  "Major motivation: keep autoreloads idempotent"
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.session :as session]
            [frereth.apps.log-viewer.frontend.socket :as web-socket]
            [frereth.apps.shared.session-socket :as session<->socket]
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
  ;; Q: Is this worth its own ns?
  (when-not (repl/alive?)
    (println "Trying to connect REPL websocket")
    ;; FIXME: Pass in the host/port as opts
    (repl/connect "ws://localhost:9001")))

(defn configure
  "Create System definition that's ready to ig/init"
  [{:keys [::lamport/clock
           ::session<->socket/connection
           ::session/manager
           ::repl
           ::web-socket/wrapper]
    :as current}]
  (console.log "Configuring system based on\n"
               (keys current)
               "\namong\n"
               current)
  {::lamport/clock clock
   ::session<->socket/connection (into {::lamport/clock (ig/ref ::lamport/clock)
                                        ::session/manager (ig/ref ::session/manager)
                                        ::web-socket/wrapper (ig/ref ::web-socket/wrapper)}
                                  connection)
   ::session/manager manager
   ::repl repl
   ::web-socket/wrapper (into {::lamport/clock (ig/ref ::lamport/clock)}
                              wrapper)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef do-begin
  :args (s/cat :state ::state-atom
               :initial ::opts)
  :ret ::state)
(defn do-begin
  [state-atom initial]
  (swap! state
         (fn [current]
           (ig/init
            (or current
                (configure initial))))))

(comment
  (println state))
