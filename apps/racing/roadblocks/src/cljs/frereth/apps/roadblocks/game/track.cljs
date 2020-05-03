(ns frereth.apps.roadblocks.game.track
  (:require
   [clojure.spec.alpha :as s]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(defonce world (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn define-world!
  []
  (.info js/console "Defining World")
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
        _ (.info js/console "Building curve from" points)
        curve (THREE/CatmullRomCurve3. (clj->js points) true)
        curve-points (.getPoints curve 50)
        _ (.info js/console "Building geometry from" curve-points)
        geometry (.setFromPoints (THREE/BufferGeometry.) curve-points)
        _ (.info js/console "Creating Material")
        material (THREE/LineBasicMaterial. (clj->js {:color 0xff0000}))
        curve-object (THREE/Line. geometry material)
        scene (THREE/Scene.)]
    (.info js/console "Have scene created")
    (.add scene curve-object)
    (set! (.-background scene) (THREE/Color. 0x000000))
    (reset! world scene)))

(defn do-physics
  "This triggers all the interesting pieces"
  [time-stamp]
  @world)
