(ns frereth.apps.roadblocks.window-manager
  "The outer 'root' piece that actually manipulates the DOM

  At least conceptually similar to a Window Manager under X"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.ui :as ui]
   [frereth.apps.shared.worker :as worker]
   [integrant.core :as ig]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::animating?
  (s/and #(instance? Atom %)
         #(boolean? (deref %))))

(s/def ::camera #(instance? THREE/Camera %))

(s/def ::canvas-dimensions (s/and
                            #(instance? Atom %)
                            #(s/valid? ::ui/dimensions-2 %)))

;; This is really just a place-holder while I prove
;; out the concept
(s/def ::cube #(instance? THREE/Mesh %))

(s/def ::renderer #(instance? THREE/WebGLRenderer %))

(s/def ::resize-event #(= (type %) js/UIEvent))

(s/def ::resize-handler
  (s/fspec
   :args (s/cat :worker-manager ::worker/manager
                :resize-event ::resize-event)
   ;; Event handler called for side-effects
   :ret any?))

(s/def ::scene #(instance? THREE/Scene %))

(s/def ::graphics (s/keys :req [::animating?
                                ::camera
                                ::cube
                                ::canvas-dimensions
                                ::renderer
                                ::scene]))

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
  (if-let [workers-atom (::worker/workers worker-manager)]
    (let [workers @workers-atom]
      (doseq [worker
              (vals workers)]
        (lamport/do-tick clock)
        ;; This is wrong.
        ;; It's going to cause the cube to resize. Need to figure out
        ;; how much space each face takes up an screen and resize "each"
        ;; worker appropriately.
        ;; But it's a start.
        (.postMessage worker (serial/serialize (assoc new-dims
                                                      :frereth/action :frereth/resize
                                                      ::lamport/clock @clock)))))
    (throw (ex-info "Missing workers among manager" (clj->js this)))))

(s/def resize-handler ::resize-handler)
(defn resize-handler
  [{:keys [::canvas-dimensions
           ::ui/camera
           ::ui/renderer]
    :as this}
   evt]
  (let [canvas (.querySelector js/document "#root")
        width (.-clientWidth canvas)
        height (.-clientHeight canvas)
        new-dims {::ui/width width
                  ::ui/height height}]
    (assert canvas)
    (assert canvas-dimensions)
    (when (ui/should-resize-renderer? @canvas-dimensions
                                      new-dims)
      ;; (send-resize is getting a nil ::worker/manager.
      ;; Odds are, it comes from here.
      (send-resize! (select-keys this [::worker/manager ::lamport/clock])
                    new-dims)

      (ui/resize-renderer-to-display-size! renderer new-dims)
      (ui/fix-camera-aspect! camera new-dims))))

(s/fdef build-scene
  :args (s/cat :canvas ::canvas)
  :ret ::graphics)
(defn build-scene
  [canvas]
  {:pre [canvas]}
  (let [renderer (THREE/WebGLRenderer. #js {:antialias true
                                            :canvas canvas})
        scene (THREE/Scene.)

        ;; Render onto our own spinning cube
        cube-dim 1
        geometry (THREE/BoxGeometry. cube-dim cube-dim cube-dim)

        fov 75
        aspect 2
        near 0.1
        far 5
        camera (THREE/PerspectiveCamera. fov aspect near far)]
    (set! (.-z (.-position camera)) 2)
    (let [color 0xffffff
          intensity 1
          light (THREE/DirectionalLight. color intensity)]
      (.set (.-position light) -1 2 4)  ; distinct from set!
      (.add scene light)  ; side-effect

      ;; TODO: Need a placeholder texture until the worker is
      ;; up and running
      (let [loader (THREE/TextureLoader.)
            ;; Q: Does lighting make any sense here?
            material (THREE/MeshPhongMaterial. #js {:map (.load loader "static/place-holder.png")})
            cube (THREE/Mesh. geometry material)]
        (.add scene cube)
        {::animating (atom true)
         ::camera camera
         ;; Should probably just return the Canvas
         ;; instead.
         ;; Especially since this is stateful.
         ;; Or maybe not...there's a lot of dubiousness
         ;; floating around.
         ::canvas-dimensions (atom {::ui/width -1
                                    ::ui/height -1})
         ::cube cube
         ::renderer renderer
         ::scene scene}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::root
  [_ {:keys [::lamport/clock]
      worker-manager ::worker/manager
      :as this}]
  (let [canvas (.querySelector js/document "#root")
        {:keys [::cube]
         :as graphics} (build-scene canvas)
        this (into this graphics)
        size-sender (partial resize-handler this)]
    (.addEventListener js/window "resize" size-sender)
    (size-sender nil)  ; trigger initial sizing signal

    (defmethod worker/handle-worker-message :frereth/render
      [{:keys [::worker/need-dom-animation?]
        :as worker-manager}
       action world-key worker event
       {:keys [:frereth/texture]
        worker-clock ::lamport/clock
        :as raw-message}]
      (lamport/do-tick clock worker-clock)
      ;; Need to update the Material.
      ;; Which seems like it probably means a recompilation.
      ;; Oh well. There aren't a lot of alternatives.
      (let [material (.-material cube)]
        (set! (.-texture material) texture)
        (set! (.-needsUpdate texture) true))
      (when need-dom-animation?
        (js/requestAnimationFrame (fn [clock-tick]
                                    ;; It seems wrong to call this directly
                                    (.postMessage worker (serial/serialize
                                                          {:frereth/action :frereth/render-frame
                                                           ::lamport/clock @clock}))))))

    (assoc this
           ::resize-handler size-sender
           ;; Start w/ a bogus definition to force the initial resize
           ::canvas-dimensions (atom {::ui/width -1
                                      ::ui/height -1}))))

(defmethod ig/halt-key! ::root
  [_ {size-sender ::resize-handler}]
  (.removeEventListener js/window "resize" size-sender)
  (remove-method worker/handle-worker-message :frereth/render))
