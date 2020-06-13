(ns frereth.apps.shared.event-queue
  "Implement the Event Queue pattern.

  This version is meant to be simple, not efficient.

  In particular, your event handlers must be fast."
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.specs :as specs]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::event-type keyword?)
(s/def ::payload map?)
(s/def ::handler (s/fspec :args (s/cat :payload ::payload)
                          :ret any?))
(s/def ::handler-key int?)
(s/def ::handlers (s/map-of ::handler-key ::handler))
(s/def ::registrations (s/map-of ::event-type ::handlers))
;; Q: Do I want this?
#_(s/def ::registration-atom (s/and ::specs/atom
                                  #(s/valid? ::registrations (deref %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef build
  :args nil
  :ret ::registrations)
(defn build
  "Create a new event queue"
  []
  {})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::queue
  [_ __]
  (atom (build)))

(s/fdef register
  :args (s/cat :this ::registrations :event-type ::event-type :handler ::handler)
  :ret (s/keys :req [::handler-key ::registrations]))
(defn register
  [this event-type handler]
  ;; This isn't exactly efficient, but it's easy
  (if (contains? this event-type)
    (let [current-handlers (event-type this)
          next-key (inc (reduce max (keys current-handlers)))
          result (assoc-in this [event-type next-key] handler)]
      {::registrations result
       ::handler-key next-key})
    {::registrations (assoc this event-type {0 handler})
     ::handler-key 0}))

(s/fdef de-register
  :args (s/cat :this ::registrations
               ;; This would be cleaner if we don't need to
               ;; specify the event-type.
               ;; Just make the handler-key globally unique and have
               ;; done with it.
               ;; Of course, that makes the data structures more
               ;; complex, since I can no longer key the handlers
               ;; off just the event-type.
               ;; Or maybe I'd need a pair of data structures. One
               ;; for the event-type/handler matches and another
               ;; as an index into that...it doesn't seem obvious.
               ;; This isn't all that onerous.
               :event-type ::event-type
               :handler-key ::handler-key)
  :ret ::registrations)
(defn de-register
  "Stop a handler from listening to an event"
  [this event-type handler-key]
  (if-let [handlers (event-type this)]
    (update this event-type
            #(dissoc % handler-key))
    this))

(s/fdef publish!
  :args (s/cat :this ::registrations
               :event-type ::event-type
               :payload ::payload))
(defn publish!
  "Send an event to the queue"
  [this event-type payload]
  (let [handlers (vals (event-type this))]
    (doseq [handler handlers]
      (handler payload))))
