(ns backend.event-bus
  "Event bus for internal(?) communications

  This seems a little silly. But it doesn't hurt to try to
  encapsulate the details"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.weald.logging :as log]
   [frereth.weald.specs :as weald]
   [integrant.core :as ig]
   [manifold.bus :as bus]
   [manifold.stream :as strm]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::bus #(instance? (class (bus/event-bus)) %))

(s/def ::event-bus (s/keys :req [::bus]))

;; It seems like this really could be anything
(s/def ::message any?)

;; This is mostly a guess. It looks like any immutable value will work
(s/def ::topic any?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::event-bus
  [_ {:keys [::stream-generator]
      :or {stream-generator strm/stream}}]
  {::bus (bus/event-bus stream-generator)})


(s/fdef publish!
  :args (s/cat :log-state ::weald/state-atom
               :event-bus ::event-bus
               :topic ::topic
               :message ::message)
  :ret any?)
(defn publish!
  [log-state-atom
   {:keys [::bus]
    :as event-bus}
   topic
   message]
  ;; FIXME: Honestly, need a way to flag events that get published
  ;; with no handlers.
  ;; I don't think manifold offers that possibility.
  (swap! log-state-atom #(log/debug %
                                    ::publish!
                                    "Top"
                                    {::topic topic
                                     ::bus bus}))
  (let [success (bus/publish! bus topic message)]
    (swap! log-state-atom #(log/debug %
                                      ::publish!
                                      "Published"
                                      {::success success}))
    success))

(s/fdef do-subscribe
  :args (s/cat :event-bus ::event-bus
               :topic ::topic)
  :ret strm/source?)
(defn do-subscribe
  [{:keys [::bus]
    :as event-bus}
   topic]
  (bus/subscribe bus topic))
