(ns client.propagate
  "Forward tap objects to the log viewer web socket"
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [renderer.lib :as lib]
            [client.registrar :as registrar]))

(def connections (atom {}))

(s/fdef connector
  :args (s/cat :world-stop-signal :frereth/world-stop-signal
               :send-message! :frereth/message-sender!)
  :ret :frereth/renderer->client)
(defn connector
  [log-mult world-stop-signal send-message!]
  (swap! connections assoc world-stop-signal send-message!)
  (let [real-dst (async/chan)]
    (async/tap log-mult real-dst)
    (async/go-loop []
      (when-let [msg (async/<! real-dst)]
        (send-message! msg)
        (recur))))

  (add-tap send-message!))

(defmethod ig/init-key ::monitor
  [_ {:keys [::log-chan]
      :as opts}]
  (let [multiplexer (async/mult log-chan)
        ;; Q: What is this?
        ;; It seems like it should actually be a partial
        registration-handler (registrar/do-register-world ::log-viewer (partial connector multiplexer))]
    (assoc opts
           ::multiplexer multiplexer
           ::registration-handler registration-handler)))

(defmethod ig/halt-key! ::monitor
  [_ {:keys [::multiplexer]
      :as opts}]
  ;; This is fuzzy enough that I need to verify the way that I expect
  ;; it to work.
  ;; Honestly, it probably doesn't make sense to have multiples at this
  ;; level. There's just a channel that's publishing log messages that
  ;; Worlds can possibly tap into.
  ;; Of course, we need to be able to just discard messages if there
  ;; aren't any listening Worlds.
  (async/untap-all multiplexer)
  (doseq [[stop-signal send-message!] @connections]
    (send-message! stop-signal))
  (reset! connections {})
  (-> opts
   (dissoc ::multiplexer)
   (dissoc ::registration-handler)))
