(ns backend.event-bus
  "Event bus for internal(?) communications

  This seems a little silly. But it doesn't hurt to try to
  encapsulate the details"
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [manifold.bus :as bus]
   [manifold.stream :as strm]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::bus #(instance? (class (bus/event-bus)) %))

(s/def ::event-bus (s/keys :req [::bus]))

;; This is mostly a guess. It looks like any immutable value will work
(s/def ::topic any?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::event-bus
  [_ {:keys [::stream-generator]
      :or {stream-generator strm/stream}}]
  {::bus (bus/event-bus stream-generator)})

(defn publish!
  [{:keys [::bus]
    :as event-bus} topic message]
  ;; FIXME: Honestly, need a way to flag events that get published
  ;; with no handlers.
  ;; I don't think manifold offers that possibility.
  (println "Publishing a message about" topic "to" bus)
  (let [success (bus/publish! bus topic message)]
    (println "publish! result:" success)
    success))

(s/fdef subscribe
  :args (s/cat :event-bus ::event-bus
               :topic ::topic))
(defn subscribe
  [{:keys [::bus]
    :as event-bus}
   topic]
  (bus/subscribe bus topic))
