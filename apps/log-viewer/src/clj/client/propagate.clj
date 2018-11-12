(ns client.propagate
  "Forward tap objects to the log viewer web socket"
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [renderer.lib :as lib]
            [client.registrar :as registrar]))

(def connections (atom {}))

(s/fdef connector
  :args (s/cat :world-stop-signal :frereth/world-stop-signal
               :send-message! :frereth/message-sender!)
  :ret :frereth/renderer->client)
(defn connector
  [world-stop-signal send-message!]
  (swap! connections assoc world-stop-signal send-message!)
  (add-tap send-message!))

(defmethod ig/init-key ::monitor
  [_ _]
  (registrar/do-register-world ::log-viewer connector))

(defmethod ig/halt-key! ::monitor
  [_ func]
  (doseq [[stop-signal send-message!] @connections]
    (remove-tap send-message!)
    (send-message! stop-signal))
  (reset! connections {}))
