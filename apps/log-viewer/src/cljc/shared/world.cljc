(ns shared.world
  ;; TODO: Refactor move this to frereth.apps.shared.worlds
  (:require [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [frereth.apps.shared.specs :as specs]))

#?(:cljs (enable-console-print!))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::connection-state #{::active                ; we've ACKed the browser's fork
                            ::created               ; preliminary, ready to go
                            ::disconnected          ; Still active after a websocket close
                            ::disconnect-timed-out  ; Timed out waiting for a response from disconnection signal
                            ::disconnecting         ; Web socket has been closed
                            ::failed                ; Error escaped from here
                            ;; Really only applies to the browser side.
                            ;; We've sent the web server the notification
                            ;; that this is done. Waiting to transition
                            ;; into an ::active state.
                            ::forked
                            ::forking               ; received source code. Ready to fork
                            ::fsm-error             ; Tried an illegal state transition
                            ::pending               ; browser would like to fork
                            })
(s/def ::cookie #?(:clj bytes?
                   :cljs any?))
;; This is whatever makes sense for the world implementation.
;; This seems like it will probably always be a map?, but it could very
;; easily also be a mutable Object (though that seems like a terrible
;; idea).
(s/def ::internal-state any?)

(s/def ::notification-channel ::specs/async-chan)

(s/def ::world-without-history (s/keys :req [::specs/time-in-state
                                             ::connection-state
                                             ::internal-state]
                                       :opt [::cookie
                                             ::notification-channel
                                             ;; In hindsight, making these
                                             ;; distinct was a foolish choice.
                                             ;; TODO: Consolidate them.
                                             #?(:clj :frereth/renderer->client
                                                :cljs :frereth/browser->worker)
                                             #?(:cljs :frereth/worker)]))
(s/def ::history (s/coll-of ::world-without-history))
;; This leads to other namespaces referencing ::world/world
;; which is just weird.
(s/def ::world (s/merge ::world-without-history
                        (s/keys :req [::history])))

(s/def :frereth/world-key :frereth/pid)
(s/def :frereth/worlds (s/map-of :frereth/world-key ::world))

(s/def ::fsm-transition
  (s/fspec :args (s/cat :world ::world)
           :ret ::world))
;; It seems almost silly to include the next-state.
;; As currently written, the caller knows exactly what that is,
;; because it explicitly requests that in this same function
;; call.
;; On the other hand, it should really be sending a state
;; transition signal. Then the FSM's lifecycle graph can
;; make this decision.
;; But, rather than "go to ::active, but make sure it's currently
;; ::pending first" that will be more along the lines of "send
;; ::activate signal"...the next state in that case isn't
;; necessarily known in advance. But it also makes this function
;; pointless.
;; FIXME: Decide what to do about this.
;; (current implementation doesn't use the :next-state parameter,
;; so this is a lie)
(s/def ::legal-transition?
  (s/nilable (s/fspec :args (s/cat :world ::world
                                   :next-state ::connection-state)
                      :ret boolean?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;; Q: Worth making this generally available?
(s/fdef now
  :args nil
  :ret inst?)
(defn now
  []
  #?(:clj (java.util.Date.)
     :cljs (js/Date.)))

(s/fdef ctor
  :args (s/cat :initial-state ::internal-state)
  :ret ::world)
(defn ctor
  [initial-state]
  {::connection-state  ::created
   ::history []
   ::internal-state initial-state
   ::specs/time-in-state (now)})

;;; TODO: Add a fsm.cljc and make the state graph declarative.
(s/fdef update-world-connection-state
  :args (s/or :simple (s/cat :world-map :frereth/worlds
                             :world-key :frereth/world-key
                             :connection-state ::connection-state)
              :checked (s/cat :world-map :frereth/worlds
                              :world-key :frereth/world-key
                              :connection-state ::connection-state
                              :pre-check ::legal-transition?)
              :extras (s/cat :world-map :frereth/worlds
                             :world-key :frereth/world-key
                             :connection-state ::connection-state
                             :pre-check ::legal-transition?
                             :transition ::fsm-transition))
  :ret :frereth/worlds)
(defn update-world-connection-state
  ;; The importance of keeping this distinct from changes to the
  ;; world's internal state cannot be overemphasized.
  "Trigger change in connection FSM"
  ([world-map world-key connection-state]
   (#?(:clj println
       :cljs console.log)
    ::update-world-connection-state
    "Updating world connection-state to"
    connection-state)
   (update world-map world-key
           (fn [world]
             (let [history (::history world)
                   previous (dissoc world ::history)]
               (-> world
                   (assoc ::specs/time-in-state (now)
                          ::connection-state connection-state)
                   (update ::history conj previous))))))
  ([world-map world-key connection-state pre-check]
   (when pre-check
     (println ::update-world-connection-state "Verifying legal FSM transition"))
   (let [current (get world-map world-key)
         next-state
         (if (or (not pre-check)
                 (pre-check current))
           connection-state
           ::fsm-error)]
     (update-world-connection-state world-map world-key next-state)))
  ([world-map world-key connection-state pre-check transition]
   (let [updated
         (update-world-connection-state world-map
                             world-key
                             connection-state
                             pre-check)]
     (println ::update-world-connection-state
              "Calling transition function on world")
     (pprint (get world-map world-key))
     (update updated world-key transition))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef get-world
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret (s/nilable ::world))
(defn get-world
  [world-map world-key]
  (get world-map world-key))

(s/fdef state-match?
  :args (s/cat :world ::world
               :state ::connection-state))
(defn state-match?
  [world state]
  (= (::connection-state world)
     state))

(s/fdef get-by-state
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key
               :connection-state ::connection-state)
  :ret :frereth/worlds)
(defn get-by-state
  [world-map connection-state]
  ;; It seems like there must be a good way to reuse the existing
  ;; structure for this.
  ;; This isn't it.
  #_(hash-map (filter (fn [[world-id world]]
                        (state-match? world connection-state))
                      world-map))
  ;; Maybe a transducer?

  ;; This should work
  (reduce (fn [acc [world-id world]]
            (if (state-match? world connection-state)
              (assoc acc world-id world)
              acc))
          {}
          world-map))

(comment
  (let [world-map {:a {::connection-state 1}
                   :b {::connection-state 1}
                   :c {::connection-state 2}}]
    #_(apply hash-map (filter (fn [[world-id world]]
                              (state-match? world 2))
                              world-map))
    (filter (fn [[world-id world]]
              (state-match? world 1))
            world-map)
    #_(mapcat (filter (fn [[world-id world]]
                      (state-match? world 2))
                      world-map))
    )
  )

(s/fdef get-world-in-state
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key
               :connection-state ::connection-state)
  :ret (s/nilable ::world))
(defn get-world-in-state
  [world-map world-key state]
  (#?(:clj println :cljs console.log) "Looking for matching" state "world")
  (if-let [world (get-world world-map world-key)]
    (do
      (#?(:clj println :cljs console.log) "Found the world")
      (if (state-match? world state)
        (do
          (#?(:clj println :cljs console.log) "State matches")
          world)
        (#?(:clj println :cljs console.log) "Looking for" state
                                            "\nbut world is in"
                                            (::connection-state world))))
    (#? (:clj println :cljs console.log) "No world matching\n"
                                         world-key
                                         "\namong\n"
                                         (keys world-map))))

(s/fdef get-active
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret (s/nilable ::world))
(defn get-active
  [world-map world-key]
  (get-world-in-state world-map world-key ::active))

;; The browser does not correctly cope with the need for
;; the browser->worker parameter which it really should
;; have long before we get here.
(s/fdef activate-forked
  :args (s/cat :world-map ::world-map
               :world-key ::world-key
               :client #?(:clj :frereth/renderer->client
                          :cljs :frereth/browser->worker))
  :fn (s/and #(= ::pending (-> % :args :world ::connection-state))
             #(or (= ::active (:ret %))
                  (= ::fsm-error (:ret %))))
  :ret ::world)
(defn activate-forked
  [world-map world-key client]
  (update-world-connection-state world-map world-key ::active
                                 (fn [world]
                                   (= (::connection-state world) ::forking))
                                 (fn  [world]
                                   (assoc world
                                          #?(:clj :frereth/renderer->client
                                             :cljs :frereth/browser->worker)
                                          client))))

(s/fdef add-pending
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key
               #?@(:cljs [:notification-channel ::notification-channel])
               #?@(:clj [:cookie ::cookie])
               :initial-state ::internal-state)
  :ret :frereth/worlds)
(defn add-pending
  "Set up a new world that's waiting for the connection signal"
  [world-map world-key #?(:cljs notification-channel
                          :clj cookie) initial-state]
  (let [world-map (assoc world-map
                         world-key (ctor initial-state))]
    (update-world-connection-state world-map world-key ::pending
                                   nil
                                   #(assoc %
                                           #?@(:clj [::cookie cookie])
                                           #?@(:cljs [::notification-channel notification-channel])))))

;; TODO: Rename this to mark-disconnected
(s/fdef disconnected
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret :frereth/worlds)
(defn disconnected
  [world-map world-key]
  (update-world-connection-state world-map world-key ::disconnected))

(s/fdef get-pending
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret (s/nilable ::world))
(defn get-pending
  [world-map world-key]
  (get-world-in-state world-map world-key ::pending))

;; TODO: Add a macro to define these mark-state functions
;; and their specs
(defn mark-disconnect-timeout
  [world-map world-key]
  (update-world-connection-state world-map world-key ::disconnect-time-out))

(s/fdef mark-disconnecting
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret :frereth/worlds)
(defn mark-disconnecting
  [world-map world-key]
  (update-world-connection-state world-map world-key ::disconnecting))

(s/fdef mark-forked
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key
               #?@(:cljs [:worker :frereth/worker]))
  :ret (s/nilable ::world))
(defn mark-forked
  [world-map world-key #?(:cljs worker)]
  (update-world-connection-state world-map world-key
                                 ::forked
                                 (fn [{:keys [::connection-state]
                                       :as world}]
                                   (= connection-state ::forking))
                                 #?(:cljs
                                    (fn [transitioned]
                                      ;; There's some duplicated effort
                                      ;; between this and mark-forking.
                                      ;; It's silly to assoc the cookie
                                      ;; and worker in both.
                                      ;; Though possibly worth verifying
                                      ;; that they match.
                                      (assoc transitioned
                                             :frereth/worker worker)))))

(s/fdef mark-forking
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key
               ;; FIXME: Specs
               :cookie any?
               :raw-key-pair any?)
  :ret (s/nilable ::world))
(defn mark-forking
  [world-map world-key cookie raw-key-pair]
  (update-world-connection-state world-map world-key
                                 ::forking
                                 (fn [{:keys [::connection-state]
                                       :as world}]
                                   (= connection-state ::pending))
                                 (fn [transitioned]
                                   ;; There's some duplicated effort
                                   ;; between this and mark-forking.
                                   ;; It's silly to assoc the cookie
                                   ;; and worker in both.
                                   ;; Though possibly worth verifying
                                   ;; that they match.
                                   (assoc transitioned
                                          ::cookie cookie
                                          ::key-pair raw-key-pair))))

(defn mark-generic-failure
  [world-map world-key]
  (update-world-connection-state world-map world-key ::failed))

(s/fdef trigger-disconnection!
  :args (s/cat :world ::world))
(defn trigger-disconnection!
  [world]
  ;; Need to send a signal to the world to do whatever it needs to
  ;; disconnect.
  (throw (ex-info "write this" {})))
