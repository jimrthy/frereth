(ns frereth.apps.roadblocks.game.runners.red
  "Behavior for a racer who's fiery and irresponsible"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef define-group
  :args nil
  :ret ::ui/group)
(defn define-group
  []
  (.log js/console "Building Red")
  (let [width 1.5
        height 1.5
        depth 1.5
        geometry  (THREE/BoxGeometry. width height depth)
        material (THREE/MeshBasicMaterial. #js {:color 0xff0000})]
    ;; This doesn't really satisfy the spec, since Mesh derives
    ;; from Object3D rather than Group.
    ;; The thing is, there are a lot of other details that need
    ;; to be tracked in here. Like animations.
    ;; So this is just a starting point
    (THREE/Mesh. geometry material)))
