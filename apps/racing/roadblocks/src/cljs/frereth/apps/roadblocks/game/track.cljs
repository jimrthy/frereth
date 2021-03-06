(ns frereth.apps.roadblocks.game.track
  "Represent the current track"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::curve-position (s/tuple number? number? number?))
(s/def ::curve-positions (s/coll-of ::curve-position))
(s/def ::forward-velocity (s/and number? (complement neg?)))
(s/def ::position (s/and number? (complement neg?)))

(s/def ::world (s/keys :req [::ui/curve
                             ::ui/group]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(def +standard-v+ 10)  ; meters / second

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef points->curve
  :args (s/cat :positions ::curve-positions)
  :ret ::ui/curve)
(defn points->curve
  [positions]
  (.info js/console "Converting positions to seq of vectors")
  (let [;; Creating this is a mess. Oh well. It's still just hacking
        ;; together a basic idea
        points (mapv (fn [coord]
                       (apply #(new THREE/Vector3. %1 %2 %3) coord))
                     positions)
        _ (.info js/console "Building curve from" points)]
    (THREE/CatmullRomCurve3. (clj->js points) true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef generate-curve-position!
  :args (s/cat :boundary number?)
  :ret ::curve-position)
(defn generate-curve-position!
  "Generates a track position in the cube between
  (-boundary -boundary -boundary) and (boundary boundary boundary)"
  [boundary]
  (let [max (* 2 boundary)
        x (- (rand-int max) boundary)
        y (- (rand-int max) boundary)
        z (- (rand-int max) boundary)]
    [x y z]))

;; Q: What should I name this?
;; Group seems too tightly coupled w/ implementation.
;; Node seems...well, maybe it's exactly what I want.
(s/fdef define-group
  :args (s/cat :curve-positions ::curve-positions)
  :ret ::world)
(defn define-group
  [positions color]
  (.info js/console "Defining" color " color track group based around" (clj->js positions))
  (let [curve (points->curve positions)
        curve-points (.getPoints curve 50)
        _ (.info js/console "Building geometry from" curve-points)
        geometry (.setFromPoints (THREE/BufferGeometry.) curve-points)
        _ (.info js/console "Creating Material")
        ;; Other interesting properties:
        ;; :dashSize - "both the gap with the stroke." Default 3
        ;; :gapSize - size of the gap. Default 1
        ;; :scale - scale of the dashed part of a line. Default is 1
        material (THREE/LineDashedMaterial. (clj->js {:color color
                                                      :gapSize 2
                                                      :linewidth 3}))
        curve-object (THREE/Line. geometry material)
        group (THREE/Group.)]
    (.info js/console "Have scene created")
    (.add group curve-object)
    {::ui/curve curve
     ::ui/group group}))

(s/fdef step!
  :args (s/cat :world ::world
               :time-stamp ::ui/timestamp))
(defn step!
  "This triggers all the interesting pieces"
  ;; This is really the place where three.js is a terrible match for
  ;; clojurescript.
  ;; I don't want to update the scenegraph in place.
  [world time-stamp]
  nil)
