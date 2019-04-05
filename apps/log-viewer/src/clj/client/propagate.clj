(ns client.propagate
  "Forward tap objects to the log viewer web socket"
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [frereth.apps.shared.specs]
            [frereth.cp.shared.specs :as cp-specs]
            [frereth.cp.shared.util :as cp-util]
            [integrant.core :as ig]
            [renderer.lib :as lib]
            [client.registrar :as registrar]
            [clojure.repl :refer (pst root-cause)])
  (:import clojure.lang.ExceptionInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::connection-atom
  (s/and ::cp-specs/atom
         ;; This is actually a map of world stop (disconnect?) signals
         ;; => the function to send signals to that world.
         #(let [pred (s/map-of symbol? fn?)]
            (-> % deref pred))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef connector
  :args (s/cat :connections ::connection-atom
               :log-mult any?  ; Q: What is this?
               :world-stop-signal :frereth/world-stop-signal
               :send-message! :frereth/message-sender!)
  :ret :frereth/renderer->client)
(defn connector
  [connections log-mult world-stop-signal send-message!]
  (swap! connections assoc world-stop-signal send-message!)
  (let [real-dst (async/chan)]
    (async/tap log-mult real-dst)
    (async/go-loop []
      (when-let [msg (async/<! real-dst)]
        (println "(debugging connector)" msg)
        (send-message! msg)
        (recur))))

  (add-tap send-message!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::monitor
  [_ {:keys [::log-chan]
      :as opts}]
  (println "Setting up propagation monitoring for " log-chan)
  (let [multiplexer (async/mult (:backend.system/ch log-chan))
        connections (atom {})
        ;; Q: What is this?
        ;; It seems like it should actually be a partial
        ;; Actually, this part is pretty broken.
        ;; This returns the function for the Renderer to create a
        ;; ::log-viewer app when it connects.
        ;; That isn't awful, but the name is deceptive.
        registration-handler (registrar/do-register-world-creator ::log-viewer
                                                                  (partial connector
                                                                           connections
                                                                           multiplexer))]
    (assoc opts
           ::connection-atom connections
           ::multiplexer multiplexer
           ;; FIXME: Come up with a better name for this key
           ::registration-handler registration-handler)))

(defmethod ig/halt-key! ::monitor
  [_ {:keys [::connection-atom ::multiplexer]
      :as opts}]
  ;; This is fuzzy enough that I need to verify the way that I expect
  ;; it to work.
  ;; Honestly, it probably doesn't make sense to have multiples at this
  ;; level. There's just a channel that's publishing log messages that
  ;; Worlds can possibly tap into.
  ;; Of course, we need to be able to just discard messages if there
  ;; aren't any listening Worlds.
  (async/untap-all multiplexer)
  (if connection-atom
    (doseq [[stop-signal send-message!] @connection-atom]
      (try
        (send-message! stop-signal)
        (catch ExceptionInfo ex
          (println "Problem" ex "trying to send" stop-signal "using"
                   send-message! "\n" (ex-data ex))
          (println "Root:\n" (root-cause ex))
          (pst))
        (catch Exception ex
          (println "Problem" ex "trying to send" stop-signal "using"
                   send-message!)
          (println "Root:\n" (root-cause ex))
          (pst))))
    (println "Missing connection atom among"
             (keys opts)
             "\nin"
             (cp-util/pretty opts)))
  (-> opts
      (dissoc ::connection-atom)
      (dissoc ::multiplexer)
      (dissoc ::registration-handler)))
