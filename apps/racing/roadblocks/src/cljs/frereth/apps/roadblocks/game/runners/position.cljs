(ns frereth.apps.roadblocks.game.runners.position
  "This handles the 'physics' as racers run along the track"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::acceleration number?)

(s/def ::delta-t (s/and number?
                        (complement neg?)))

;; Percentage of position around the ::track
(s/def ::position (s/and number?
                           (complement neg?)
                           #(< % 1)))

(s/def ::mob (s/keys :req [::position
                           ::ui/position
                           ::ui/forward-vector
                           ::ui/up-vector
                           ::velocity]))

;; This is a deliberate over-simplification.
;; It seems to get significantly more complex once we introduce multiple
;; lanes
(s/def ::track ::ui/curve)

;; For our purposes, velocity cannot be negative.
;; Although, for something like running into a bomb,
;; maybe that shouldn't be true
(s/def ::velocity (s/and number?
                         (complement neg?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef calculate-new-position-and-orientation
  :args (s/cat :track ::track
               :mob ::mob
               :delta-t ::delta-t)
  :ret ::mob)
(defn calculate-new-position-and-orientation
  ;; Q: Does this need to cope with things like collisions?
  ;; Or is this all better somehow suited to a real
  ;; physics library?
  [track
   {:keys [::position
           ::velocity]
    :as racer}
   delta-t]
  #_(.info js/console "Calculationg new position on" track "after" delta-t "seconds")
  (let [new-position-on-curve (mod (+ position (* velocity delta-t)) 1)
        ;; Q: What are the performance implications for creating
        ;; new Vector3Ds each time, rather than doing an update
        ;; in place?
        new-location (.getPointAt track new-position-on-curve)
        ;; N.B. this is a unit vector
        new-direction (.getTangentAt track new-position-on-curve)
        up (THREE/Vector3. (.-x new-direction)
                           (- (.-z new-direction))
                           (.-y new-direction))]
    #_(.log js/console "New position on" track "after" delta-t "seconds at position" new-position-on-curve "based on velocity" velocity "is" new-location)
    #_(js/alert "New track position after" delta-t " seconds at position " new-position-on-curve " based on velocity " velocity " is " new-location)
    (assoc racer
           ::direction new-direction
           ::position new-position-on-curve
           ::ui/position new-location
           ::ui/up-vector up)))
