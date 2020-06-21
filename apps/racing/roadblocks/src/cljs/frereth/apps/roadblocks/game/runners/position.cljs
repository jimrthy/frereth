(ns frereth.apps.roadblocks.game.runners.position
  "This handles the 'physics' as racers run along the track"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ;; This doesn't compile
   [glMatrix :as gl-matrix]
   ;; FIXME: This usage needs to go away.
   ;; It's fine for a proof of concept where I'm happy using
   ;; shadow-cljs.
   ;; But it's a completely non-standard feature that's been completely
   ;; rejected by the core compiler, and I cannot justify relying on it.
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::acceleration number?)
(s/def ::forward-acceleration ::acceleration)
;; Sign is just a judgment call.
;; Make it negative, so we can add it to the delta-y
;; when jumping/falling.
;; In meters / second
(s/def ::gravity-acceleration (s/and ::acceleration
                                     neg?))

;;
(s/def ::delta-t (s/and number?
                        (complement neg?)))

;; Percentage of position around the ::track
(s/def ::position (s/and number?
                           (complement neg?)
                           #(< % 1)))

;; Current velocity for leaping/falling
(s/def ::delta-y number?)

(s/def ::mob (s/keys :req [::delta-y
                           ::forward-acceleration
                           ::position
                           ::ui/position
                           ::ui/forward-vector
                           ::ui/up-vector
                           ::velocity]
                     :opt [::gravity-acceleration]))

;; This is a deliberate over-simplification.
;; It seems to get significantly more complex once we introduce multiple
;; lanes
(s/def ::track ::ui/curve)

;; For our purposes, velocity cannot be negative.
;; Although, for something like running into a bomb,
;; maybe that shouldn't be true.
;; Note that this is about velocity along the track,
;; which is very different than delta-y.
(s/def ::velocity (s/and number?
                         (complement neg?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; 135 degrees in radians
(def +three-quarters-pi+ (* 0.75 Math/pi))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef calculate-position-in-air
  :ret (s/keys :opt [::delta-y]
               :req [::ui/position]))
(defn calculate-position-in-air
  "Life gets more complicated if the racer is in mid-air"
  [old-location-on-track
   new-location-on-track
   delta-y
   physical-location
   up
   gravity-acceleration
   delta-t]
  (if (not delta-y)
    {::ui/position new-location-on-track}
    (let [delta-t (/ delta-t 1000)  ; convert from milliseconds to seconds
          x0 (.-x physical-location)
          y0 (.-y physical-location)
          z0 (.z physical-location)
          p0 (gl-matrix.vec3/fromValues x0 y0 z0)

          new-vec-on-track (gl-matrix.vec3/fromValues (.-x new-location-on-track)
                                                      (.-y new-location-on-track)
                                                      (.-z new-location-on-track))

          ;; In theory, this is where the jumper should land if there weren't a
          ;; "horizontal" component
          old-vec-on-track (gl-matrix.vec3/fromValues (.-x old-location-on-track)
                                                      (.-y old-location-on-track)
                                                      (.-z old-location-on-track))
          ;; Change in "horizontal" position
          track-delta (gl-matrix.vec3/create)
          _ (gl-matrix.vec3/subtract track-delta new-vec-on-track old-vec-on-track)

          ;; Change in "vertical" position
          up-vec (gl-matrix.vec3/fromValues (.-x up) (.-y up) (.-z up))
          vertical-delta-1 (gl-matrix.vec3/create)
          _ (gl-matrix.vec3/scale vertical-delta-1 (* delta-y delta-t))

          ;; Where the jumper would be if there weren't any "horizontal"
          ;; component to the movement, nor track to intersect
          p1 (gl-matrix.vec3/create)
          _ (gl-matrix.vec3/add p1 p0 vertical-delta-1)

          ;; Where the jumper will be if they don't intersect with the
          ;; track
          p2 (gl-matrix.vec3/create)
          _ (gl-matrix.vec3/add p2 p1 track-delta)

          ;; Test intersection w/ track by comparing the direction of
          ;; the vectors from p0 and p2 to new-vec-on-track
          start-to-target (gl-matrix.vec3/create)
          _ (gl-matrix.vec3/subtract start-to-target new-vec-on-track p0)
          end-to-target (gl-matrix.vec3/create)
          _ (gl-matrix.vec3/subtract start-to-target new-vec-on-track p2)

          angle-in-radians (gl-matricx.vec3/angle start-to-target end-to-target)]
      ;; Really should be right at 0, then jump to pi
      (.info js/console "Angle:" angle-in-radians)
      (if (> angle +three-quarters-pi+)
        ;; Could add this as an `or` clause with the top check about
        ;; delta-y.
        ;; but it requires checking p1 against p0 and verifying whether
        ;; we intersected the track. So it will be better to handle
        ;; the case separately
        {::ui/position new-location-on-track}
        (let [delta-y (+ delta-y (* gravity-acceleration delta-t))]
          {::delta-y delta-y
           ;; This isn't right.
           ;; Also need to adjust for direction of travel.
           ;; With movement along the up-vector cancelled out.
           ;; At least, I think I want the latter part.
           ;; I guess I should try the easy version first
           ;; and see how it looks.
           ::ui/position (THREE/Vector. x1 y1 z1)})))))

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
   {:keys [::delta-y
           ::position
           ::velocity]
    physical-location ::ui/position
    :as racer}
   delta-t]
  #_(.info js/console "Calculationg new position on" track "after" delta-t "seconds")
  (let [current-position-on-track (.getPointAt track position)
        new-position-on-curve (mod (+ position (* velocity delta-t)) 1)
        ;; Q: What are the performance implications for creating
        ;; new Vector3Ds each time, rather than doing an update
        ;; in place?
        new-location-on-track (.getPointAt track new-position-on-curve)
        ;; N.B. this is a unit vector
        new-direction (.getTangentAt track new-position-on-curve)
        ;; Rotate 90 degrees around the x axis
        up (THREE/Vector3. (.-x new-direction)
                           (- (.-z new-direction))
                           (.-y new-direction))
        {:keys [::delta-y]
         new-location ::ui/position} (calculate-position-in-air current-position-on-track new-location-on-track delta-y physical-location up -9.8 delta-t)]
    #_(.log js/console "New position on" track "after" delta-t "seconds at position" new-position-on-curve "based on velocity" velocity "is" new-location)
    #_(js/alert "New track position after" delta-t " seconds at position " new-position-on-curve " based on velocity " velocity " is " new-location)
    (assoc racer
           ::delta-y delta-y
           ::direction new-direction
           ::position new-position-on-curve
           ::ui/position new-location
           ::ui/up-vector up)))
