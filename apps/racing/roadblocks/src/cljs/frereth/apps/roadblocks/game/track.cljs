(ns frereth.apps.roadblocks.game.track
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::forward-velocity (s/and number? (complement neg?)))
(s/def ::position (s/and number? (complement neg?)))

(s/def ::racer (s/keys :req [::object-3d
                             ::forward-velocity
                             ::position]))
(s/def ::racers (s/coll-of ::racer))

(s/def ::world (s/keys :req [::ui/group]
                       :opt [::racers]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn define-sample-curve
  []
  (let [positions [[0 0 0]
                   [20 20 -20]
                   [10 10 -10]
                   [20 -10 0]
                   [-20 10 -10]
                   [-5 0 -5]]
        _ (.info js/console "Converting positions to seq of vectors")
        ;; Creating this is a mess. Oh well. It's still just hacking
        ;; together a basic idea
        points (mapv (fn [coord]
                       (apply #(new THREE/Vector3. %1 %2 %3) coord))
                     positions)
        _ (.info js/console "Building curve from" points)]
    (THREE/CatmullRomCurve3. (clj->js points) true)))

(s/fdef define-group
  :args (s/cat :curve ::curve)
  :ret ::ui/group)
(defn define-group
  [curve]
  (.info js/console "Defining World")
  (let [curve-points (.getPoints curve 50)
        _ (.info js/console "Building geometry from" curve-points)
        geometry (.setFromPoints (THREE/BufferGeometry.) curve-points)
        _ (.info js/console "Creating Material")
        material (THREE/LineBasicMaterial. (clj->js {:color 0x006666}))
        curve-object (THREE/Line. geometry material)
        group (THREE/Group.)]
    (.info js/console "Have scene created")
    (.add group curve-object)
    group))

(s/fdef step!
  :args (s/cat :group ::ui/group
               :time-stamp ::ui/timestamp))
(defn step!
  "This triggers all the interesting pieces"
  ;; This is really the place where three.js is a terrible match for
  ;; clojurescript.
  ;; I don't want to update the scenegraph in place.
  [group time-stamp]
  nil)
