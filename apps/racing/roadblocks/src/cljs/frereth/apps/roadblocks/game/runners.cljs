(ns frereth.apps.roadblocks.game.runners
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

(enable-console-print!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; feels wrong to make these "global."
;; But it also doesn't seem worth defining its own system, yet.
;; They definitely shouldn't live in this ns
(defn create-initial-state
  []
  ;; I don't want to define this here. But compilation
  ;; breaks if I just `declare` it before trying to call it
  {::camera {::fov nil
             ::aspect nil
             ::near nil
             ::far nil
             ::ui/camera nil}
   ::destination {::ui/width nil
                  ::ui/height nil
                  ::ui/renderer nil}
   ::ui/scene nil
   ::world []})

(defonce state (atom (create-initial-state)))
;; Q: How much sense does it make for this to be a def vs. defonce?
;; A: Since this implementation is purely a throw-away PoC, who cares
;; either way?
(def global-clock (atom 0))
;; Can this animate itself?
;; Currently, in Firefox, at least, the main thread has to trigger each
;; frame.
(defonce has-animator
  (boolean js/requestAnimationFrame))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn define-world!
  []
  (let [w 512
        h 512
        fov 75
        aspect (/ w h)
        near 0.1
        far 0.5
        camera (THREE/PerspectiveCamera. fov aspect near far)]
    ;; icck
    (set! (.-z (.-position camera)) 2)

    (let [scene (THREE/Scene.)

          add-light! (fn
                       [scene]
                       (set! (.-background scene) (THREE/Color. 0x888888))
                       (let [color 0xffffff
                             intensity 1
                             light (THREE/DirectionalLight. color intensity)]
                         (.set (.-position light) -1 2 4)
                         (.add scene light)))]
      (add-light! scene)
      (let [width 1
            height 1
            depth 1
            geometry (THREE/BoxGeometry. width height depth)
            make-instance! (fn [geometry color x]
                             (let [mat (THREE/MeshPhongMaterial. #js {:color color})
                                   cube (THREE/Mesh. geometry mat)]
                               (.add scene cube)
                               (set! (.-x (.-position cube)) x)
                               cube))
            cubes [(make-instance! geometry 0xaaaa00 -2)
                   (make-instance! geometry 0xaa0000 0)
                   (make-instance! geometry 0x0000aa 2)]
            ;; TODO: Honestly, I'd rather have something like this than
            ;; an OffscreenCanvas.
            ;; Even if OffscreenCanvas becomes widely supported, there are
            ;; limits to the number of available graphics contexts, and it
            ;; seems like each must consume one.
            ;; TODO: Verify that
            ;; TODO: Get back to this approach
            mock-canvas #js {
                             :addEventListener (fn [type-name listener options]
                                                 (.log js/console "Trying to add an event listener:"
                                                       type-name
                                                       "options:" options))
                             :getContext (fn [context-name attributes]
                                           ;; This may be a deal-killer for my current plan.
                                           ;; Alternatively, this may be an opportunity to write
                                           ;; a buffering renderer that forwards along calls to
                                           ;; happen on the "outside."
                                           ;; Q: Is this worth the effort it would take?
                                           (throw (js/Error. "Need a WebGL context")))}
            ;; Leaving this around, because, really, it's what I want
            ;; to do.
            #_(THREE/WebGLRenderer. #js {:canvas (clj->js mock-canvas)})]
        (swap! state into {::camera {::fov fov
                                     ::aspect aspect
                                     ::near near
                                     ::far far
                                     ::ui/camera camera}
                           ::destination {::ui/width w
                                          ::ui/height h}
                           ::ui/scene scene
                           ::world cubes})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef render-and-animate!
  :args (s/cat :renderer :ui/renderer
               :time-stamp (s/and number? pos?))
  :ret any?)
(defn render-and-animate!
  "This triggers all the interesting pieces"
  [renderer time-stamp]
  (let [time (/ time-stamp 1000)
        {:keys [::destination
                ::ui/scene
                :world]
         {:keys [::ui/camera]
          :as camera-wrapper} ::camera
         :as state} @state
        ;; Using a map variant for side-effects feels wrong.
        animation (map-indexed (fn [ndx obj]
                                 (let [speed (inc (* ndx 0.1))
                                       rot (* time speed)
                                       rotation (.-rotation obj)]
                                   (set! (.-x rotation) rot)
                                   (set! (.-y rotation) rot)))
                               world)]
    ;; Realize that lazy sequence
    (dorun animation)
    (.info js/console
           "Trying to render from the camera" camera
           "a" (type camera)
           "on renderer" renderer
           "a" (type renderer)
           "from the state-keys" (clj->js (keys state)))
    (try
      (.render renderer scene camera)
      (catch :default ex
        (.error js/console ex
                "[WORKER] Trying to use"
                renderer
                "to render"
                scene)
        (throw ex)))))
