(ns frereth.apps.roadblocks.game.runners
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

(enable-console-print!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; This feels suspiciously like it would make more
;; sense in the ui ns
(s/def ::fov number?)
(s/def ::aspect number?)
(s/def ::near (s/and number?
                     (complement neg?)))
(s/def ::far (s/and number?
                    (complement neg?)))

;; It would clean things up a bit if I
;; renamed this to ::camera-wrapper
;; FIXME: This does not belong in here.
;; The scenes should not have any idea how they're getting
;; rendered.
(s/def ::camera (s/keys :req [::fov
                              ::aspect
                              ::near
                              ::far
                              ::ui/camera]))

(s/def ::world (s/coll-of ::ui/mesh))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; feels wrong to make these "global."
;; But it also doesn't seem worth defining its own system, yet.

;; I'd rather just `declare` this here and define it later,
;; but the compiler won't let me.
(defn create-initial-state
  []
  ;; It's important that this gets called before
  ;; render-and-animate!
  ;; It configures global state that the latter uses
  {::camera {::fov nil
             ::aspect nil
             ::near nil
             ::far nil
             ::ui/camera nil}
   ::ui/scene nil
   ::world []})

(defonce state-atom (atom (create-initial-state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn define-world!
  []
  (.info js/console "Defining World")
  (let [w 512
        h 512
        fov 75
        aspect (/ w h)
        near 0.1
        far 10
        camera (THREE/PerspectiveCamera. fov aspect near far)]
    ;; icck
    (set! (.-z (.-position camera)) 2)

    (let [;; Q: Does it make sense to define the scene in here?
          ;; It seems like it would make much more sense to just
          ;; return the
          scene (THREE/Scene.)
          width 1
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
          ;; to do. Though it (and mock-canvas) no longer belong here
          #_(THREE/WebGLRenderer. #js {:canvas (clj->js mock-canvas)})
          add-light! (fn
                       []
                       (let [color 0xffffff
                             intensity 1
                             light (THREE/DirectionalLight. color intensity)]
                         (.set (.-position light) -1 2 4)
                         (.add scene light)))]
      (add-light!)
      (set! (.-background scene) (THREE/Color. 0x888888))
      (swap! state-atom into {::camera {::fov fov
                                        ::aspect aspect
                                        ::near near
                                        ::far far
                                        ::ui/camera camera}
                              ::ui/scene scene
                              ::world cubes}))))

(s/fdef do-physics
  :args (s/cat :time-stamp (s/and number? pos?))
  :ret ::ui/scene)
(defn do-physics
  "This triggers all the interesting pieces"
  [time-stamp]
  (let [time (/ time-stamp 1000)
        {:keys [::destination
                ::ui/scene
                ::world]
         {:keys [::ui/camera]
          :as camera-wrapper} ::camera
         :as state} @state-atom
        ;; Using a map variant to set up side-effects feels wrong.
        animation (map-indexed (fn [ndx obj]
                                 (let [speed (inc (* ndx 0.1))
                                       rot (* time-stamp speed)
                                       rotation (.-rotation obj)]
                                   (set! (.-x rotation) rot)
                                   (set! (.-y rotation) rot)))
                               world)]
    ;; Realize that lazy sequence
    (dorun animation)
    #_(.info js/console
           "Trying to render" scene
           "\nwhich consists of"(clj->js world)
           "\nfrom" (clj->js state)
           "\nfrom the camera" camera
           "a" (type camera)
           "from the state-keys" (clj->js (keys state)))
    ;; Don't try to do the rendering in here.
    ;; We shouldn't even have access to the camera at
    ;; this point.
    #_(try
      (.render renderer scene camera)
      (catch :default ex
        (.error js/console ex
                "[WORKER] Trying to use"
                renderer
                "to render"
                scene)
        (throw ex)))
    scene))

(defn resize!
  [{:keys [::ui/width
           ::ui/height]
    :as new-dims}]
  (let [{:keys [::ui/renderer]
         camera-wrapper ::camera
         :as state} @state-atom]
    (when renderer
      (ui/resize-renderer-to-display-size! renderer
                                           new-dims)
      (let [camera (::ui/camera camera-wrapper)]
        (ui/fix-camera-aspect! camera new-dims))))
  (swap! state-atom
   (update ::camera
           assoc
           ::aspect (/ width height))))

(defn clean-up!
  []
  (let [{:keys [::ui/scene
                ::world]} @state-atom]
    (doseq [mesh world]
      (.dispose (.-geometry mesh))
      (.dispose (.-map (.-material mesh))))
    (.dispose scene))
  (reset! state-atom (create-initial-state)))
