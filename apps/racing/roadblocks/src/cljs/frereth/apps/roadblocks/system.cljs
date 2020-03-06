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
           ::repl]
    websock-options ::web-socket/options
    :as current}]
  (.log js/console "Configuring system based on\n"
        (clj->js (keys current))
        "\namong\n"
        (clj->js current))
  ;; FIXME: This seems like it needs an "Attract" worker.
  ;; But that isn't something that makes sense at this level.
  ;; Q: Where does it belong?
  {::lamport/clock clock
   ::session<->socket/connection (into {::lamport/clock (ig/ref ::lamport/clock)
                                        ::session/manager (ig/ref ::session/manager)
                                        ::web-socket/options websock-options
                                        ::worker/manager (ig/ref ::worker/manager)}
                                  connection)
   ::session/manager manager
   ;; TODO: This is pretty integral to the entire point.
   ;; Q: But does it still make sense in a shadow-cljs world?
   #_#_::repl repl
   ::window-manager/root {::lamport/clock (ig/ref ::lamport/clock)
                          ::worker/manager (ig/ref ::worker/manager)}
   ::worker/manager {::lamport/clock (ig/ref ::lamport/clock)
                     ::session/manager (ig/ref ::session/manager)}})

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
