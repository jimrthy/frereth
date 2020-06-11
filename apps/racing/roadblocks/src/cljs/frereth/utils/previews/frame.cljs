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

(defn load-skybox!
  []
  (let [loader (THREE/CubeTextureLoader.)]
    (.load loader (clj->js ["static/images/skybox/rainbow/rainbow_lf.png"
                            "static/images/skybox/rainbow/rainbow_rt.png"
                            "static/images/skybox/rainbow/rainbow_up.png"
                            "static/images/skybox/rainbow/rainbow_dn.png"
                            "static/images/skybox/rainbow/rainbow_ft.png"
                            "static/images/skybox/rainbow/rainbow_bk.png"]))))

(defn setup-runner-preview
  "Look at a gallery of racers"
  [element]
  (runners/define-world!)
  (let [{:keys [::ui/scene]
         {:keys [::ui/camera]
          :as camera-wrapper} ::runners/camera
         :as runner-state} @runners/state-atom

        _ (when-not camera
            (.error js/console
                    "Mising ::ui/camera in"
                    (clj->js camera-wrapper)
                    "among"
                    (clj->js @runners/state-atom)))
        controls (trackball/TrackballControls. camera element)
        sky-box (load-skybox!)
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

    (set! (.-background scene) sky-box)

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
  [scene camera track-curve racer velocity post-camera-resize-callback! move-camera-callback!]
  (let [mob-atom (atom {::position/position 0
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

      ;; Since this doesn't do anything, there isn't any point to calling it yet.
      ;; Q: Would there ever be?
      ;; A: Well...what about dynamic tracks?
      ;; Temporary objects that live on different parts of the track seem like
      ;; one thing.
      ;; Tracks that actually morph around seem like something different, and
      ;; are probably out of scope.
      #_(track/step! track-group time-stamp)

      (let [{:keys [::position/direction
                    ::ui/position
                    ::ui/up-vector]
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
              z' (.-z position)

              x-up (.-x up-vector)
              y-up (.-y up-vector)
              z-up (.-z up-vector)]
          (set! (.-x (.-position racer)) x')
          (set! (.-y (.-position racer)) y')
          (set! (.-z (.-position racer)) z')

          (set! (.-x (.-up racer)) x-up)
          (set! (.-y (.-up racer)) y-up)
          (set! (.-z (.-up racer)) z-up)

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
  "This is for the 'real' camera that peeks over the Runner's shoulder"
  [follower-camera follower-helper
   {:keys [::ui/position ::position/direction ::ui/up-vector]}]
  (let [dx (.-x direction)
        dy (.-y direction)
        dz (.-z direction)
        ;; Current object position
        x1 (.-x position)
        y1 (.-y position)
        z1 (.-z position)

        x-up (.-x up-vector)
        y-up (.-y up-vector)
        z-up (.-z up-vector)
        ;; Want to look at the track ahead from slightly behind and
        ;; above the racer.
        ;; TODO: want a little left/right offset also
        x2 (+ (- x1 (* 5 dx)) (* 3 x-up))
        y2 (+ (- y1 (* 5 dy)) (* 3 y-up))
        z2 (+ (- z1 (* 5 dz)) (* 3 z-up))]
    (set! (.-x (.-position follower-camera)) x2)
    (set! (.-y (.-position follower-camera)) y2)
    (set! (.-z (.-position follower-camera)) z2)

    (set! (.-x (.-up follower-camera)) x-up)
    (set! (.-y (.-up follower-camera)) y-up)
    (set! (.-z (.-up follower-camera)) z-up)
    (.lookAt follower-camera (+ x1 (* 4 dx)) (+ y1 (* 4 dy)) (+ z1 (* 4 dz)))
    (when follower-helper
      (.update follower-helper))))

(defn build-track-with-runner
  "Generate a sample track w/ runner cruising along"
  []
  (let [position-atom (atom [])]
    ;; For a demo, this looks surprisingly good
    (dotimes [_ 6]
      ;; Q: Does cljs support the volaile equivalent for locals?
      (swap! position-atom #(conj % (track/generate-curve-position! 100))))
    (let [positions (vec @position-atom)
          {:keys [::ui/curve
                  ::ui/group]
           :as track-world} (track/define-group positions 0x006666)
          track-length (.getLength curve)
          velocity (/ track/+standard-v+ track-length)
          red-racer (red/define-group)
          scene (THREE/Scene.)]
      (.info js/console
             "Setting up a racer to cruise around"
             track-length
             "meters on"
             curve)
      (.add scene group)
      (.add scene red-racer)
      #_(.info js/console "Adding curve to left of" (clj->js positions) "\na" (type positions) "\nstarting with" (clj->js (first positions)))
      (let [positions-left (mapv (fn [[x y z]]
                                   #_(.info js/console "New track position at" (- x 2) y z)
                                   [(- x 2) y z])
                                 positions)
            #_#__ (.info js/console "Setting up new track to left of original at" (clj->js positions-left))
            {:keys [::ui/curve ::ui/group] :as left-track} (track/define-group positions-left 0xaaaa00)]
        (.add scene group))
      (let [positions-left (mapv (fn [[x y z]]
                                   #_(.info js/console "New track position at" (- x 2) y z)
                                   [(+ x 2) y z])
                                 positions)
            #_#__ (.info js/console "Setting up new track to left of original at" (clj->js positions-left))
            {:keys [::ui/curve ::ui/group] :as left-track} (track/define-group positions-left 0xaa00aa)]
        (.add scene group))
      {::ui/curve curve
       ::racer red-racer
       ::scene scene
       ::velocity velocity})))

(defn do-build-common-camera
  "Build the camera that follows the racer"
  ;; Refactoring common functionality between a couple of different
  ;; views
  []
  (let [fov 85
        w 512
        h 512
        aspect 1
        near 0.1
        far 200]
    (THREE/PerspectiveCamera. fov aspect near far)))

(defn setup-runner-on-track
  "Draw the track from just behind the racer"
  [element]
  ;; It would be nice to coordinate this w/ setup-trackpreview to
  ;; compare what's going on side by side.
  ;; I keep forgetting that these are two totally different tracks, and
  ;; that gets confusing.
  (let [{:keys [::racer ::scene ::velocity]
         track-curve ::ui/curve
         :as track-with-runner} (build-track-with-runner)

        camera (do-build-common-camera)

        post-camera-resize-callback nil

        on-camera-move! (partial on-camera-move! camera nil)

        sky-box (load-skybox!)

        step! (build-runner-step-along-track scene camera track-curve racer velocity post-camera-resize-callback on-camera-move!)]
    (set! (.-background scene) sky-box)

    (.info js/console "Setting up keydown listener for" element)
    (set! (.-tabindex element) 1)
    (set! (.-zIndex (.-style element)) 1)
    ;; TODO: Experiment w/ zIndex (sp?)
    (.addEventListener element "click"
                       (fn [evt]
                         (.info js/console "Click:" evt "on"
                                element
                                "\ntabindex:" (.-tabindex element)
                                "\nz-index:" (.-z-index element)
                                "\nstyle:" (.-style element))))
    (.addEventListener element "focus"
                       (fn []
                         (.info js/console "Has focus. Press a key")))
    (.addEventListener element "keydown"
                       (fn [evt]
                         (js/alert "key down")
                         (let [key-code (.-keyCode evt)]
                           (.info js/console "Key down:" key-code)
                           (when (= key-code ui/+keycode-space+)
                             (.info js/console "TODO: Jump")))))
    {::render! step!
     ::element element}))

(defn setup-track-preview
  "Build a debug view of the track with the racer and a Camera Helper to try to get an idea what's going on"
  [element]
  (let [{:keys [::racer ::scene ::velocity]
         track-curve ::ui/curve
         :as track-with-runner} (build-track-with-runner)]
    (set! (.-background scene) (THREE/Color. 0x888888))
    ;; TODO: The "real-camera" really needs to take in the full track
    ;; size so I can view the entire thing.
    (let [fov 60
          w 512
          h 512
          aspect (/ w h)
          near 0.1
          far 200
          real-camera (THREE/PerspectiveCamera. fov aspect near far)
          controls (trackball/TrackballControls. real-camera element)

          follower-camera (do-build-common-camera)
          follower-helper (THREE/CameraHelper. follower-camera)

          post-camera-resize-callback (fn []
                                        ;; controls is even weirder, since it doesn't exist
                                        ;; in the "real" thing.
                                        (.handleResize controls)
                                        (.update controls))

          on-camera-move! (partial on-camera-move! follower-camera follower-helper)

          step! (build-runner-step-along-track scene real-camera track-curve racer velocity post-camera-resize-callback on-camera-move!)]
      (.add scene follower-helper)
      ;; TODO: Set up this and the fov so I can see the entire track
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
    ;; TODO: Move this (and the one in the real game loops) to the top
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
