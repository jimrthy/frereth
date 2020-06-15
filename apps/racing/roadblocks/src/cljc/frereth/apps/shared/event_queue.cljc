(ns frereth.apps.shared.event-queue
  "Implement the Event Queue pattern.

  This version is meant to be simple, not efficient."
  (:require
   [#?(:clj clojure.core.async
       :cljs cljs.core.async) :as async]
   [#? (:clj clojure.core.async.impl.protocols
        :cljs cljs.core.async.impl.protocols) :as async-protocols]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.specs :as specs]
   [integrant.core :as ig]))

#?(:cljs (enable-console-print!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::event-type keyword?)
(s/def ::payload map?)
(s/def ::handler (s/fspec :args (s/cat :payload ::payload)
                          :ret any?))
(s/def ::handler-key int?)
(s/def ::handlers (s/map-of ::handler-key ::handler))
(s/def ::registrations (s/map-of ::event-type ::handlers))
(s/def ::registry (s/and ::specs/atom
                         #(s/valid? ::registrations (deref %))))

;; Really, this is a go-loop
(s/def ::event-loop #(satisfies? async-protocols/ReadPort %))

(s/def ::writer #(satisfies? async-protocols/WritePort %))

(s/def ::queue (s/keys :req [::event-loop ::registry ::writer]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef build
  :args nil
  :ret ::queue)
(defn build
  "Create a new event queue"
  []
  (let [pub (async/chan)
        registry (atom {})]
    {::event-loop (async/go-loop []
                    (let [[event-type payload] (async/<! pub)]
                      (when-not (= event-type ::done)
                        (let [handlers (vals (event-type @registry))]
                          #_(println "Have" (count handlers)
                                   "handler for a(n)"
                                   event-type)
                          (doseq [handler handlers]
                            (handler payload)))
                        (recur))))
     ::registry registry
     ::writer pub}))

(s/fdef tear-down!
  :args (s/cat :this ::queue)
  :ret any?)
(defn tear-down!
  [{:keys [::writer]}]
  (async/go
    (async/>! writer [::done nil])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::queue
  [_ __]
  (build))

(defmethod ig/halt-key! ::queue
  [_ this]
  (tear-down! this))

(s/fdef register!
  :args (s/cat :this ::queue
               :event-type ::event-type
               :handler ::handler)
  :ret ::handler-key)
(defn register!
  [{:keys [::registry]
    :as this} event-type handler]
  (let [registrations @registry
        next-key (if-let [current-handlers (event-type registrations)]
                   ;; This isn't exactly efficient, but it's easy
                   ;; It's really tempting to come up with some
                   ;; sort of better scheme that allows re-using
                   ;; discarded keys and keeps from needing to
                   ;; do this reduce.
                   ;; But, really, this is for small data. So this
                   ;; should be fine.
                   (inc (reduce max (keys current-handlers)))
                   0)]
    (swap! registry
           assoc-in [event-type next-key] handler)
    next-key))

(s/fdef de-register!
  :args (s/cat :this ::queue
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
  :ret any?)
(defn de-register!
  "Stop a handler from listening to an event"
  [{:keys [::registry]
    :as this} event-type handler-key]
  (let [registrations @registry]
    (when-let [handlers (event-type registrations)]
      (swap! registry update event-type
             #(dissoc % handler-key)))))

(s/fdef publish!
  :args (s/cat :this ::registrations
               :event-type ::event-type
               :payload ::payload))
(defn publish!
  "Send an event to the queue"
  [{:keys [::writer]
    :as this}
   event-type payload]
  (async/go
    (async/>! writer [event-type payload])))
