(ns server.log-gen
  "Simulate a frereth server that generates some logs"
  (:require [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn send-log-eventually
  [halted n]
  (when-not (realized? halted)
    (Thread/sleep (rand-int 5000))
    (tap> {::counter n})
    (recur halted (inc n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::samples
  [_ _]
  (let [halted (promise)]
    {::halted halted
     ::next (future (send-log-eventually halted 0))}))

(defmethod ig/halt-key! ::samples
  [_ {:keys [::halted]
      :as this}]
  (deliver halted ::doesnt-matter))
