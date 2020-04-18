(ns frereth.utils.previews.frame
  "Provide something similar to devcards/NuBank workspaces in a Canvas"
  (:require
   ["three" :as THREE]
   ;; Of course it isn't this easy
   [#_"three.examples.jsm.controls.TrackballControls"
    "three/examples/jsm/controls/TrackballControls":as trackball]))

(defn make-scene
  [element]
  (let [scene (THREE/Scene.)
        fov 45
        aspect 2
        near 0.1
        far 5
        camera (THREE/PerspectiveCamera. fov aspect near far)]
    ;; Considering the next line, isn't this redundant?
    (set! (.-z (.-position camera)) 2)
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

(defn setup-red-scene
  [element]
  (let [scene-info (make-scene element)
        geometry (THREE/BoxBufferGeometry. 1 1 1)
        material (THREE/MeshPhongMaterial. #js{:color "red"})
        mesh (THREE/Mesh. geometry material)]
    (.add (::scene scene-info) mesh)
    (assoc scene-info ::mesh mesh)))

(defn setup-blue-scene
  [element]
  (let [scene-info (make-scene element)
        geometry (THREE/BoxBufferGeometry. 1 1 1)
        material (THREE/MeshPhongMaterial. #js{:color "blue" :flatShading true})
        mesh (THREE/Mesh. geometry material)]
    (.add (::scene scene-info) mesh)
    (assoc scene-info ::mesh mesh)))

(defn renderer-factory
  "Returns a rendering function"
  [element]
  (let [element-name (.-preview (.-dataset element))
        factories {:blue setup-blue-scene
                   :red setup-red-scene}
        factory (-> element-name keyword factories)
        {:keys [::camera
                ::controls
                ::mesh
                ::scene]
         :as scene-info} (factory element)]
    ;; Realistically, each preview/card will have its own renderer
    {::render! (fn [renderer {:keys [::width ::height]
                              :as rect} time]
                 #_(.info js/console
                        "Inside local-render! for" element-name
                        "\nscene-info:" (clj->js scene-info)
                        "\nbounding rectangle:" rect
                        "at time" time)
                 (set! (.-y (.-rotation mesh)) (* 0.1 time))
                 (set! (.-aspect camera) (/ width height))
                 (.updateProjectionMatrix camera)
                 (.handleResize controls)
                 (.update controls)
                 (.render renderer scene camera))
     ::element element}))

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

(defn render!
  [renderer scenes time]
  (let [time (* 0.001 time)]
    (resize-renderer-to-display-size! renderer)

    (.setScissorTest renderer false)
    #_(.info js/console "Clearing background to" (.getClearColor renderer))
    (.clear renderer true true)
    (.setScissorTest renderer true)

    ;; Adjust the canvas to keep it from bouncing up and down
    (let [canvas (.-domElement renderer)
          transform (str "translatey(" (.-scrollY js/window) "px)")]
      (set! (.-transform (.-style canvas)) transform))

    (doseq #_[scene-info scenes] [scene-key (keys scenes)]
           #_(.info js/console "Rendering the" scene-key)
           #_(render-scene-info! renderer scene-info)
           (let [{:keys [::element
                         ::render!]} (get scenes scene-key)
                 bounding-rect (js->clj (.getBoundingClientRect element) :keywordize-keys)
                 ;; Q: Why didn't the js->clj work?
                 #_{:keys [:left :right :bottom :top :width :height]
                    :as bounding-rect}
                 ;; This kind of destructuring is annoying
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
             #_(.info js/console
                      "Rendering a scene around a bounding box"
                      bounding-rect
                      "\ni.e. " left "-" right "by" bottom "-" top
                      (str "that is"
                           (when is-off-screen " not"))
                      "currently on-screen, based on a"
                      (.-clientHeight canvas) "x" (.-clientWidth canvas) "canvas")
             (when-not is-off-screen
               (let [positive-y-up-bottom (- (.-clientHeight canvas) bottom)]
                 #_(.info js/console
                          "Setting Scissor/Viewport from"
                          (str "(" left ", " positive-y-up-bottom ")")
                          "to"
                          (str "(" width ", " height ")"))
                 (.setScissor renderer left positive-y-up-bottom width height)
                 (.setViewport renderer left positive-y-up-bottom width height)

                 (render! renderer {::width width ::height height} time)))))
    (js/requestAnimationFrame (partial render! renderer scenes))))

(defn ^:dev/after-load ^:export start!
  [parent]
  (let [canvas (.querySelector js/document "#app")
        renderer (THREE/WebGLRenderer. #js{:canvas canvas :alpha true})
        ;; This returns a js/NodeList, which is not ISeqable
        node-list (.querySelectorAll js/document "[data-preview]")
        node-seq (array-seq node-list)
        ;; It really seems like this should be dynamic
        scenes (reduce (fn [acc element]
                         (let [preview-name (.-preview (.-dataset element))
                               local-render! (renderer-factory element)]
                           ;; This isn't good enough.
                           ;; Also need the element so we can handle all
                           ;; the details about things like off-screen
                           ;; checks and setting the scissor/viewport
                           (assoc acc preview-name local-render!)))
                       {}
                       node-seq)]
    (.info js/console "Scenes to manage:" (clj->js scenes))
    (js/requestAnimationFrame (partial render! renderer scenes))))
