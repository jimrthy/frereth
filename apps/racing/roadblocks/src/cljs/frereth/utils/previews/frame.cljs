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

(defn build-runner-step-along-track
  "Return a function to be called every animation frame"
  ;; FIXME: Need to convert the callbacks into a map if
  ;; I want to get serious about this approach.
  ;; I'm thinking
  ;; ::on-camera-resize
  ;; ::on-runner-move
  ;; I have to remember: this is strictly a proof-of-concept
  [scene camera track-curve racer post-camera-resize-callback! move-camera-callback!]
  (let [velocity (/ 1 120)         ; have each lap take 2 minutes
        mob-atom (atom {::position/position 0
                        ::position/velocity velocity})]
    ;; This function handles the common side-effects of moving the racer
    ;; around the track.
    (fn
      [renderer
       {:keys [::width ::height]
        :as rect}
       previous-time-stamp
       time-stamp]
      #_(.log js/console "Adjusting track position from time" previous-time-stamp "to" time-stamp)
      (set! (.-aspect camera) (/ width height))
      (.updateProjectionMatrix camera)

      (when post-camera-resize-callback!
        (post-camera-resize-callback!))

      ;; Since this doesn't do anything, there isn't any point to call it yet.
      #_(track/step! track-group time-stamp)

      (let [{:keys [::ui/position
                    ::position/direction]
             ;; It's tempting to just have this calculate the
             ;; transformation matrix.
             ;; But I'd still need the direction (TODO: and up vector)
             ;; to set the camera
             :as transformation} (swap! mob-atom
                                        (fn [mob]
                                          (position/calculate-new-position-and-orientation track-curve
                                                                                           mob
                                                                                           (if previous-time-stamp
                                                                                             (- time-stamp previous-time-stamp)
                                                                                             0))))]
        (when move-camera-callback!
          (move-camera-callback! transformation))
        ;; TODO: Need to set the racer's rotation (or quaternion?) based on direction
        ;; and up vectors.
        ;; Q: What *is* the up vector here?
        ;; I think I have an intuitive grasp, but I can't describe it
        ;; formally.
        ;; It's tempting to project the tangent onto, say, the x-y plane.
        ;; Then rotate it 90 degrees and normalize that vector (with 0
        ;; z component) as "up".
        ;; But that would cheat the fun sideways loop-the-loops and
        ;; swoops like you get around the curves at Nascar
        (let [x' (.-x position)
              y' (.-y position)
              z' (.-z position)]
          (set! (.-x (.-position racer)) x')
          (set! (.-y (.-position racer)) y')
          (set! (.-z (.-position racer)) z')

          (.lookAt racer
                   (+ x' (.-x direction))
                   (+ y' (.-y direction))
                   (+ z' (.-z direction)))))
      (try
        (.render renderer scene camera)
        (catch :default ex
          (.error js/console ex
                  "[PREVIEW FRAME] Failed to use "
                  renderer
                  " to render "
                  scene)
          (throw ex))))))

(defn on-camera-move!
  [follower-camera follower-helper
   {:keys [::ui/position ::position/direction ::ui/up-vector]}]
  (let [dx (.-x direction)
        dy (.-y direction)
        dz (.-z direction)
        ;; Current object position
        x1 (.-x position)
        y1 (.-y position)
        z1 (.-z position)
        ;; Want to look at the track ahead from slightly behind and
        ;; above the racer.
        ;; Really want a little left/right offset also
        x2 (+ (- x1 (* 3 dx)) (* 2 (.-x up-vector)))
        y2 (+ (- y1 (* 3 dy)) (* 2 (.-y up-vector)))
        z2 (+ (- z1 (* 3 dz)) (* 2 (.-z up-vector)))]
    (set! (.-x (.-position follower-camera)) x2)
    (set! (.-y (.-position follower-camera)) y2)
    (set! (.-z (.-position follower-camera)) z2)
    (.lookAt follower-camera (+ x1 (* 4 dx)) (+ y1 (* 4 dy)) (+ z1 (* 4 dz)))
    (when follower-helper
      (.update follower-helper))))

(defn build-track-with-runner
  []
  (let [positions [[0 0 0]
                   [40 40 -40]
                   [20 20 -20]
                   [40 -20 0]
                   [-40 20 -20]
                   [-10 0 -10]]
        {:keys [::ui/curve
                ::ui/group]
         :as track-world} (track/define-group positions)
        red-racer (red/define-group)
        scene (THREE/Scene.)]
    (.info js/console "Setting up a racer to cruise around" curve)
    (.add scene group)
    (.add scene red-racer)
    {::ui/curve curve
     ::racer red-racer
     ::scene scene}))

(defn setup-runner-on-track
  "Draw the track from just behind the racer"
  [element]
  (let [{:keys [::racer ::scene]
         track-curve ::ui/curve
         :as track-with-runner} (build-track-with-runner)

        fov 60
        w 512
        h 512
        aspect 1
        near 0.1
        far 100
        camera (THREE/PerspectiveCamera. fov aspect near far)

        post-camera-resize-callback nil

        on-camera-move! (partial on-camera-move! camera nil)

        step! (build-runner-step-along-track scene camera track-curve racer post-camera-resize-callback on-camera-move!)]
    (set! (.-background scene) (THREE/Color. 0x666666))
    {::render! step!
     ::element element}))

(defn setup-track-preview
  [element]
  (let [{:keys [::racer ::scene]
         track-curve ::ui/curve
         :as track-with-runner} (build-track-with-runner)]
    (set! (.-background scene) (THREE/Color. 0x888888))
    (let [fov 60
          w 512
          h 512
          aspect (/ w h)
          near 0.5
          far 100
          real-camera (THREE/PerspectiveCamera. fov aspect near far)
          controls (trackball/TrackballControls. real-camera element)

          follower-camera (THREE/PerspectiveCamera. fov aspect near far)
          follower-helper (THREE/CameraHelper. follower-camera)

          post-camera-resize-callback (fn []
                                        ;; controls is even weirder, since it doesn't exist
                                        ;; in the "real" thing.
                                        (.handleResize controls)
                                        (.update controls))

          on-camera-move! (partial on-camera-move! follower-camera follower-helper)

          step! (build-runner-step-along-track scene real-camera track-curve racer post-camera-resize-callback on-camera-move!)]
      (.add scene follower-helper)
      (set! (.-z (.-position real-camera)) 60)
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
        factories {:runner-on-track setup-runner-on-track
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
