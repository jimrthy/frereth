(ns backend.event-bus
  "Event bus for internal(?) communications

  This seems a little silly. But it doesn't hurt to try to
  encapsulate the details"
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [manifold.bus :as bus]
   [manifold.stream :as strm]))

(s/def ::bus #(instance? (class (bus/event-bus)) %))

(s/def ::event-bus (s/keys :req [::bus]))

(defmethod ig/init-key ::event-bus
  [_ {:keys [::stream-generator]
      :or {stream-generator strm/stream}}]
  {::bus (bus/event-bus stream-generator)})

(defn publish!
  [{:keys [::bus]
    :as event-bus} topic message]
  (bus/publish! bus topic message))

(defn subscribe
  [{:keys [::bus]
    :as event-bus} topic]
  (bus/subscribe bus topic))
