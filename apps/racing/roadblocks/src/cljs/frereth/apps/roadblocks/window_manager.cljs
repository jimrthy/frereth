(ns frereth.apps.roadblocks.window-manager
  "The outer 'root' piece that actually manipulates the DOM

  At least conceptually similar to a Window Manager under X"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.ui :as ui]
   [frereth.apps.shared.window-manager :as shared-wm]
   [frereth.apps.shared.worker :as worker]
   [integrant.core :as ig]
   ["three" :as THREE]))

(enable-console-print!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::animating?
  (s/and #(instance? Atom %)
         #(boolean? (deref %))))

(s/def ::canvas-dimensions (s/and
                            #(instance? Atom %)
                            #(s/valid? ::ui/dimensions-2 %)))

;; This is really just a place-holder while I prove
;; out the concept
(s/def ::cube ::ui/mesh)

(s/def ::resize-event #(= (type %) js/UIEvent))

(s/def ::resize-handler
  (s/fspec
   :args (s/cat :worker-manager ::worker/manager
                :resize-event ::resize-event)
   ;; Event handler called for side-effects
   :ret any?))

(s/def ::graphics (s/keys :req [::animating?
                                ::ui/camera
                                ::cube
                                ::canvas-dimensions
                                ::ui/renderer
                                ::ui/scene]))

(s/def ::root (s/merge ::graphics
                       (s/keys :req [::lamport/clock
                                     ::resize-handler
                                     ::worker/manager])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef send-resize!
  :args (s/cat :this ::root
               :new-dimensions ::ui/dimensions-2)
  ;; Called for side-effects
  :ret any?)
(defn send-resize!
  [{worker-manager ::worker/manager
    :keys [::lamport/clock]
    :as this}
   new-dims]
  {:pre [worker-manager]}
  "Notify the workers that the rendering size has changed"
  (.log js/console "send-resize! Trying to find workers-atom among"
        (clj->js (keys worker-manager))
        "from" worker-manager )
  (if-let [workers-atom (::worker/workers worker-manager)]
    (let [workers @workers-atom]
      (doseq [worker
              (vals workers)]
        (lamport/do-tick clock)
        ;; This is wrong.
        ;; It's going to cause the cube to resize. Need to figure out
        ;; how much space each face takes up an screen and resize each
        ;; worker appropriately.
        ;; But it's a start.
        (worker/send-to-worker! clock
                                worker
                                ;; FIXME: This is really a
                                ;; :frereth/event
                                ;; Although :dom/event would be
                                ;; a better name for that
                                :frereth/resize
                                new-dims)))
    (throw (ex-info "Missing workers among manager" this))))

(s/def resize-handler ::resize-handler)
(defn resize-handler
  [{:keys [::canvas-dimensions
           ::ui/camera
           ::ui/renderer]
    :as this}
   evt]
  {:pre [::canvas-dimensions]}
  (when-not renderer
    (.info js/console "road-blocks.window-manager/resize-handler called badly"
          this evt)
    (throw (ex-info "Missing renderer" this)))
  (let [canvas (.querySelector js/document "#root")]
    (assert canvas)
    ;; Verify that browser supports off-screen canvas
    ;; This is currently very limited (in that it
    ;; only really works in chrome...there's some
    ;; experimental firefox support, but it will be severely crippled
    ;; until their web workers support .requestAnimationFrame).
    ;; Even then, they don't support 2d rendering.
    ;; TODO: Better error handling.
    (assert (.-transferControlToOffscreen canvas))
    (let [width (.-clientWidth canvas)
          height (.-clientHeight canvas)
          new-dims {::ui/width width
                    ::ui/height height}]
      (when (ui/should-resize-renderer? @canvas-dimensions
                                        new-dims)
        (send-resize! (select-keys this [::worker/manager ::lamport/clock])
                      new-dims)

        (ui/resize-renderer-to-display-size! renderer new-dims)
        (ui/fix-camera-aspect! camera new-dims)))))

(s/fdef create-camera
  :args (s/cat :z-position number?)
  :ret ::ui/camera)
(defn create-camera
  [z-position]
  (let [fov 75
        aspect 2
        near 0.1
        far 5
        camera (THREE/PerspectiveCamera. fov aspect near far)]
    ;; This is obviously extremely limited and inflexible
    #_(set! (.-z (.-position camera)) 2)
    (.set (.-position camera) 0 1 2)
    camera))

(s/fdef create-floor
  :args (s/cat :texture-loader ::ui/texture-loader)
  :ret ::ui/mesh)
(defn create-floor
  [texture-loader]
  (let [plane-size 40
        repeats (/ plane-size 2)
        texture (.load texture-loader "/static/floor-check.png")]
    (set! (.-wrapS texture) THREE/RepeatWrapping)
    (set! (.-wrapT texture) THREE/RepeatWrapping)
    (set! (.-magFilter texture) THREE/NearestFilter)
    (.set (.-repeat texture) repeats repeats)
    (let [geo (THREE/PlaneBufferGeometry. plane-size plane-size)
          mat (THREE/MeshPhongMaterial. #js {:map texture
                                             :side THREE/DoubleSide})
          mesh (THREE/Mesh. geo mat)]
      (set! (.-x (.-rotation mesh)) (* (.-PI js/Math) -0.5))
      (.log js/console "Floor:" mesh)
      mesh)))

(defn create-light
  []
  (let [color 0xffffff
        intensity 1
        light (THREE/DirectionalLight. color intensity)]
    (.set (.-position light) -1 2 4)  ; distinct from set!
    light))

(s/fdef build-scene
  :args (s/cat :canvas ::canvas)
  :ret ::graphics)
(defn build-scene
  "This builds the 'outer' shell renderer

  I'm conflating abstraction layers.

  This part is conceptually in the ballpark of the X Server
  layer. It should draw either a) the login/demo screen or the
  b) authenticated end-user's window manager/desktop/shell.
  c) Or the public website with a login option (which really
  should be the same as a)"
  [canvas]
  {:pre [canvas]}
  (let [renderer (THREE/WebGLRenderer. #js {:antialias true
                                            :canvas canvas})
        scene (THREE/Scene.)
        texture-loader (THREE/TextureLoader.)

        ;; Render onto our own spinning cube
        cube-dim 1
        geometry (THREE/BoxGeometry. cube-dim cube-dim cube-dim)

        floor (create-floor texture-loader)
        camera (create-camera 2)
        light (create-light)]
    (.add scene floor)
    (.add scene light)
    #_(.add scene (.-target light))

    ;; TODO: Need to discard this texture once the Worker starts
    ;; supplying the real texture.
    ;; It isn't a huge resource sink, and it's probably a lot
    ;; less wasteful than a lot of the options I'm choosing, but
    ;; tidiness for its own sake isn't a bad thing.
    (let [texture (.load texture-loader "/static/place-holder.png")
          ;; Q: Do we want this to respond to lighting?
          material (THREE/MeshPhongMaterial. #js {:map texture})
          cube (THREE/Mesh. geometry material)]
      (.add scene cube)
      {::animating (atom true)
       ::ui/camera camera
       ;; Should probably just return the Canvas
       ;; instead.
       ;; Especially since this is stateful.
       ;; Or maybe not...there's a lot of dubiousness
       ;; floating around, and we do need to track
       ;; the "current" size
       ::canvas-dimensions (atom {::ui/width -1
                                  ::ui/height -1})
       ::cube cube
       ::floor floor  ; so we can dispose of it
       ::ui/renderer renderer
       ::ui/scene scene})))

(defn render!
  [{:keys [::cube ::ui/camera ::ui/renderer ::ui/scene]
    :as this} time]
  (let [time (* 0.001 time)]
    (set! (.-x (.-rotation cube)) time)
    (set! (.-y (.-rotation cube)) (* 1.1 time)))
  (.render renderer scene camera)
  (js/requestAnimationFrame (partial render! this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/def do-start ::shared-wm/ctor)
(defn do-start
  [{:keys [::lamport/clock
           ::shared-wm/implementation]
    worker-manager ::worker/manager
    :as this}]
  (println "roadblocks.window-manager/do-start")
  (when implementation
    ;; This is really the restart
    ;; If I were going to require applications to use Integrant, this
    ;; case should be covered by an ig/resume method.
    ;; Since I'm not, that isn't an option here, either.
    ;; Well, maybe it would be. But it seems like it would just add
    ;; needless complexity, since I have to exit that ecosystem
    ;; anyway.
    (.info js/console "Q: What do we want/need to do on a restart?"))
  (let [canvas (.querySelector js/document "#root")
        {:keys [::cube]
         :as graphics} (build-scene canvas)
        _ (.info js/console
                 "roadblocks.window-manager/build-scene returned"
                 graphics)
        this (into this graphics)
        ;; This isn't working right.
        ;; resize-handler is getting called with no
        ;; renderer. Or any of the other graphics pieces that should
        ;; have been added by build-scene
        size-sender (partial resize-handler this)]
    (.addEventListener js/window "resize" size-sender)
    (size-sender nil)  ; trigger initial sizing signal

    ;; TODO: Figure out a way to define this outside the function
    ;; scope.
    (defmethod worker/handle-worker-message :frereth/render
      [{:keys [::worker/need-dom-animation?]
        :as worker-manager}
       action world-key worker event
       {img-bmp :frereth/body
        worker-clock ::lamport/clock
        :as raw-message}]
      (.info js/console "Handling render request:" raw-message)
      (let [texture (THREE/CanvasTexture. img-bmp)
            material (.-material cube)]
        (set! (.-map material) texture)
        (set! (.-needsUpdate (.-map material)) true))
      (when need-dom-animation?
        (comment
          (js/requestAnimationFrame (fn [clock-tick]

                                      ;; This seems like it really should
                                      ;; involve a wrapper in...maybe the
                                      ;; worker-manager?
                                      (.postMessage worker (serial/serialize
                                                            {:frereth/action :frereth/render-frame
                                                             ::lamport/clock @clock})))))))
    (let [result
          (into (assoc this
                       ::resize-handler size-sender
                       ;; Start w/ a bogus definition to force the initial resize
                       ::canvas-dimensions (atom {::ui/width -1
                                                  ::ui/height -1}))
                graphics)]
      (js/requestAnimationFrame (partial render! result))
      result)))

(s/def halt! ::shared-wm/dtor!)
(defn halt!
  [{size-sender ::resize-handler
    :keys [::cube ::floor]}]
  (println "Cleaning up the concrete-blocks/wm")
  (.removeEventListener js/window "resize" size-sender)
  (remove-method worker/handle-worker-message :frereth/render)
  (doseq [mesh [cube floor]]
    (when mesh
      (.dispose (.-geometry mesh))
      (.dispose (.-map (.-material mesh))))))
