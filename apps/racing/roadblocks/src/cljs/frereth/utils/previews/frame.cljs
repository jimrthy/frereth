;;;; The original version of this was heavily influenced by
;;;; https://threejsfundamentals.org/threejs/lessons/threejs-multiple-scenes.html
;;;; and the related code at
;;;; https://github.com/gfxfundamentals/threejsfundamentals/blob/master/threejs/lessons/threejs-multiple-scenes.md
;;;; That code is Copyright 2018, Google Inc. All rights reserved.
;;;; I think

;;;; # Copyright 2018, Google Inc.
;;;; # All rights reserved.
;;;; #
;;;; # Redistribution and use in source and binary forms, with or without
;;;; # modification, are permitted provided that the following conditions are
;;;; # met:
;;;; #
;;;; #     * Redistributions of source code must retain the above copyright
;;;; #       notice, this list of conditions and the following disclaimer.
;;;; #
;;;; #     * Redistributions in binary form must reproduce the above
;;;; #       copyright notice, this list of conditions and the following disclaimer
;;;; #       in the documentation and/or other materials provided with the
;;;; #       distribution.
;;;; #
;;;; #     * Neither the name of Google Inc. nor the names of their
;;;; #       contributors may be used to endorse or promote products derived from
;;;; #       this software without specific prior written permission.
;;;; #
;;;; # THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
;;;; # "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
;;;; # LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
;;;; # A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
;;;; # OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
;;;; # SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
;;;; # LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;;;; # DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;;;; # THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;;;; # (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
;;;; # OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

;;;; is a pretty standard MIT license.

;;;; I think this probably qualifies as a derivative work,
;;;; since I've translated big chunks of it from javascript.
;;;; I don't think that google owns the copyright to this code,
;;;; so I think this falls under my overall frereth
;;;; project lincense, but I am not a lawyer.
;;;; If I've done something wrong here, then please let me know so I
;;;; can fix it.

(ns frereth.utils.previews.frame
  "Provide something similar to devcards/NuBank workspaces in a Canvas"
  (:require
   [frereth.apps.roadblocks.game.runners :as runners]
   [frereth.apps.roadblocks.game.runners.position :as position]
   [frereth.apps.roadblocks.game.runners.red :as red]
   [frereth.apps.roadblocks.game.track :as track]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]
   ["three/examples/jsm/controls/TrackballControls":as trackball]))

(defn make-scene
  [element]
  (let [scene (THREE/Scene.)
        fov 45
        aspect 2
        near 0.1
        far 5
        camera (THREE/PerspectiveCamera. fov aspect near far)]
    (.set (.-position camera) 0 1 2)
    (.lookAt camera 0 0 0)
    (.add scene camera)

    (let [color 0xffffff
          intensity 1
          light (THREE/DirectionalLight. color intensity)]
      (.set (.-position light) -1 2 4)
      (.add camera light))

    (let [controls (trackball/TrackballControls. camera element)]
      (set! (.-noZoom controls) true)
      (set! (.-noPan controls) true)

      {::camera camera
       ::controls controls
       ::element element
       ::scene scene})))

(defn setup-blue-scene
  [element]
  (let [{:keys [::camera
                ::controls
                ::scene]
         :as scene-info} (make-scene element)
        geometry (THREE/BoxBufferGeometry. 1 1 1)
        material (THREE/MeshPhongMaterial. #js{:color "blue"})
        mesh (THREE/Mesh. geometry material)]
    (.add scene mesh)
    {::render!
     (fn [renderer {:keys [::width ::height]
                    :as rect} prev-time time]
       ;; FIXME: Don't couple the physics with the animation
       (set! (.-y (.-rotation mesh)) (* 0.1 time))
       (set! (.-aspect camera) (/ width height))
       (.updateProjectionMatrix camera)
       (.handleResize controls)
       (.update controls)
       (.render renderer scene camera))
     ::element element}))

(defn setup-runner-preview
  [element]
  (runners/define-world!)
  (let [camera-wrapper (::runners/camera @runners/state-atom)
        {:keys [::ui/camera]} camera-wrapper
        _ (when-not camera
            (.error js/console
                    "Mising ::ui/camera in"
                    (clj->js camera-wrapper)
                    "among"
                    (clj->js @runners/state-atom)))
        controls (trackball/TrackballControls. camera element)
        step! (fn [renderer
                   {:keys [::width ::height]
                    :as rect}
                   previous-time-stamp
                   time-stamp]
                ;; Q: How does it make sense to get access to the camera here?
                ;; It should be in @runners/state, but that's awful.
                (set! (.-aspect camera) (/ width height))
                (.updateProjectionMatrix camera)
                ;; controls is even weirder, since it doesn't exist
                ;; in the "real" thing.
                (.handleResize controls)
                (.update controls)
                (let [scene (runners/do-physics time-stamp)
                      ;; FIXME: Using this approach, the camera does not belong
                      ;; with the runners' state
                      camera (-> runners/state-atom deref ::runners/camera ::ui/camera)]
                  (try
                    (.render renderer scene camera)
                    (catch :default ex
                      (.error js/console ex
                              "[PREVIEW FRAME] Failed to use "
                              renderer
                              " to render "
                              scene)
                      (throw ex)))))]
    (set! (.-noZoom controls) true)
    (set! (.-noPan controls) true)
    {::render! step!
     ::element element}))

(defn setup-runner-on-track
  "Draw the track from just behind the racer"
  [element]
  (throw (js/Error. "Write this")))

(defn setup-track-preview
  [element]
  (let [track-curve (track/define-sample-curve)  ; FIXME: Doesn't belong in here
        track-group (track/define-group track-curve)
        red-racer (red/define-group)
        scene (THREE/Scene.)]
    (.info js/console "Setting up a racer to cruise around" track-curve)
    (.add scene track-group)
    (.add scene red-racer)
    (set! (.-background scene) (THREE/Color. 0x000000))
    (let [fov 60
          w 512
          h 512
          aspect (/ w h)
          near 0.1
          far 100
          camera (THREE/PerspectiveCamera. fov aspect near far)
          controls (trackball/TrackballControls. camera element)

          curve-length (.getLength track-curve)
          velocity (/ 1 120)         ; have each lap take 2 minutes
          mob-atom (atom {::position/position 0
                          ::position/velocity velocity})
          step! (fn [renderer
                     {:keys [::width ::height]
                      :as rect}
                     previous-time-stamp
                     time-stamp]
                  #_(.log js/console "Adjusting track position from time" previous-time-stamp "to" time-stamp)
                  (set! (.-aspect camera) (/ width height))
                  (.updateProjectionMatrix camera)
                  ;; controls is even weirder, since it doesn't exist
                  ;; in the "real" thing.
                  (.handleResize controls)
                  (.update controls)
                  ;; Since this doesn't do anything, there isn't any point to call it yet.
                  #_(track/step! track-group time-stamp)

                  (let [{:keys [::ui/position
                                ::position/direction]} (swap! mob-atom
                                  (fn [mob]
                                    (position/calculate-new-position-and-orientation track-curve
                                                                                     mob
                                                                                     (if previous-time-stamp
                                                                                       (- time-stamp previous-time-stamp)
                                                                                       0))))]
                    ;; TODO: Need to set the racer's rotation (or quaternion?) based on direction
                    ;; and up vectors.
                    (set! (.-x (.-position red-racer)) (.-x position))
                    (set! (.-y (.-position red-racer)) (.-y position))
                    (set! (.-z (.-position red-racer)) (.-z position)))
                  (try
                    (.render renderer scene camera)
                    (catch :default ex
                      (.error js/console ex
                              "[PREVIEW FRAME] Failed to use "
                              renderer
                              " to render "
                              scene)
                      (throw ex))))]
      (set! (.-z (.-position camera)) 25)
      (set! (.-noZoom controls) true)
      (set! (.-noPan controls) true)
      {::render! step!
       ::element element})))

(defn renderer-factory
  "Returns a rendering function"
  [canvas element]
  ;; This is a ridiculous approach.
  ;; This is why Fulcro defines the factories and manually
  ;; associates them with the appropriate element.
  (let [element-name (.-preview (.-dataset element))
        factories {:blue setup-blue-scene
                   :runner-on-track setup-runner-on-track
                   :runners setup-runner-preview
                   :track setup-track-preview}
        factory (-> element-name keyword factories)]
    (factory element)))

(defn need-resize?
  [canvas width height]
  (or (not= width (.-width canvas))
      (not= height (.-height canvas))))

(defn resize-renderer-to-display-size!
  [renderer]
  (let [canvas (.-domElement renderer)
        width (.-clientWidth canvas)
        height (.-clientHeight canvas)]
    (when (need-resize? canvas width height)
      (.setSize renderer width height false)
      true)))

;;; It isn't really a problem, but it's confusing.
(defn actual-render!
  [renderer scenes previous-time time]
  (let [time (* 0.001 time)]  ; convert from milliseconds to seconds
    #_(.log js/console "Rendering delta from " previous-time " to " time)
    (resize-renderer-to-display-size! renderer)

    (.setScissorTest renderer false)
    (.clear renderer true true)
    (.setScissorTest renderer true)

    ;; Adjust the canvas to keep it from bouncing up and down
    (let [canvas (.-domElement renderer)
          transform (str "translatey(" (.-scrollY js/window) "px)")]
      (set! (.-transform (.-style canvas)) transform))

    (doseq [scene-key (keys scenes)]
           (let [{:keys [::element
                         ::render!]} (get scenes scene-key)
                 ;; Q: Why didn't the js->clj work?
                 #_{:keys [:left :right :bottom :top :width :height]
                    :as bounding-rect}
                 ;; This kind of destructuring is annoying
                 bounding-rect (js->clj (.getBoundingClientRect element) :keywordize-keys)
                 bottom (.-bottom bounding-rect)
                 height (.-height bounding-rect)
                 left (.-left bounding-rect)
                 right (.-right bounding-rect)
                 top (.-top bounding-rect)
                 width (.-width bounding-rect)
                 canvas (.-domElement renderer)
                 is-off-screen (or (< bottom 0)
                                   (> top (.-clientHeight canvas))
                                   (< right 0)
                                   (> left (.-clientWidth canvas)))]
             (when-not is-off-screen
               (let [positive-y-up-bottom (- (.-clientHeight canvas) bottom)]
                 (.setScissor renderer left positive-y-up-bottom width height)
                 (.setViewport renderer left positive-y-up-bottom width height)

                 (render! renderer {::width width ::height height} previous-time time)))))
    ;; Deliberately limit it to 10 FPS to help avoid log messages sending it off
    ;; the rails
    #_(js/setTimeout
     #(js/requestAnimationFrame (partial actual-render! renderer scenes time))
     2000)
    (js/requestAnimationFrame (partial actual-render! renderer scenes time))))

(defn ^:dev/after-load ^:export start!
  [parent]
  (let [canvas (.querySelector js/document "#app")
        renderer (THREE/WebGLRenderer. #js{:canvas canvas :alpha true})
        ;; This returns a js/NodeList, which is not ISeqable
        node-list (.querySelectorAll js/document "[data-preview]")
        node-seq (array-seq node-list)
        ;; The set of scenes really should be dynamic
        scenes (reduce (fn [acc element]
                         (let [preview-name (.-preview (.-dataset element))
                               local-render! (renderer-factory canvas element)]
                           (.info js/console "Adding scene for" preview-name)
                           ;; This isn't good enough.
                           ;; Also need the element so we can handle all
                           ;; the details about things like off-screen
                           ;; checks and setting the scissor/viewport
                           (assoc acc preview-name local-render!)))
                       {}
                       node-seq)]
    (.info js/console "Scenes to manage:" (clj->js scenes))
    ;; This will supply a default background color for scenes.
    ;; Until I render a scene that does specify a background color.
    ;; Scenes rendered after that one will switch back to the default
    ;; white background.
    ;; Actually, there's more to it than that.
    ;; This version loads with the sort of strobe light effect that
    ;; could very well trigger seizures.
    ;; The main background color is flickering between white and magenta
    ;; at 10 fps.
    ;; Eventually, it settles back down to...generally white.
    ;; And then flickers back on.
    ;; This is pretty awful.
    ;; But at least the animations are going, and I have a vague
    ;; notion about why it's awful.
    (.setClearColor renderer (THREE/Color. 0x880088))
    (js/requestAnimationFrame (partial actual-render! renderer scenes nil))))
