(ns frereth.apps.roadblocks.system
  "I'm really tempted to just straight copy/paste this from log-viewer.

  But it could easily be moved into a shared ns.

  I don't want to require every frereth app to build itself around
  integrant."
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.roadblocks.window-manager :as window-manager]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.session-socket :as session<->socket]
   [frereth.apps.shared.socket :as web-socket]
   [frereth.apps.shared.worker :as worker]
   [integrant.core :as ig]
   ;; Q: Does it make any sense to tie to something as specific as weasel?
   #_[weasel.repl :as repl]))

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

#_(defmethod ig/init-key ::repl
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
  (.log js/console "Configuring system based on\n"
        (clj->js (keys current))
        "\namong\n"
        (clj->js current))
  {::lamport/clock clock
   #_#_::session<->socket/connection (into {::lamport/clock (ig/ref ::lamport/clock)
                                        ::session/manager (ig/ref ::session/manager)
                                        #_#_::web-socket/wrapper (ig/ref ::web-socket/wrapper)
                                        ::worker/manager (ig/ref ::worker/manager)}
                                  connection)
   ::session/manager manager
   #_#_::repl repl
   #_#_::web-socket/wrapper (into {::lamport/clock (ig/ref ::lamport/clock)}
                              wrapper)
   ::window-manager/root {::lamport/clock (ig/ref ::lamport/clock)
                          ::worker/manager (ig/ref ::worker/manager)}
   ::worker/manager {::lamport/clock (ig/ref ::lamport/clock)
                     ::session/manager (ig/ref ::session/manager)
                     #_#_::web-socket/wrapper (ig/ref ::web-socket/wrapper)}
   })

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
