(ns frereth.apps.roadblocks.worker
  "Entry point for Web Worker to do real work"
  ;; Kicker about this: it probably needs a System more
  ;; than core does
  (:require
   ["three" :as THREE]))

(enable-console-print!)
(js/console.log "Worker top")


(def has-animator
  "Can this animate itself?
  Currently, in Firefox, at least, the main thread has to trigger each
  frame."
  (bool js/requestAnimationFrame))

(let [w 512
      h 512
      fov 75
      aspect (/ w h)
      near 0.1
      far 0.5
      camera (THREE/PerspectiveCamera. fov aspect near far)]
  ;; icck
  (set! (.-z (.-position camera) 2))

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
                             cube))]
      ;; feels wrong to make this a global.
      ;; But the alternatives seem worse, for current purposes.
      (def state (atom {:camera {:fov fov
                                 :aspect aspect
                                 :near near
                                 :far far
                                 :instance camera}
                        :destination {:width w
                                      :height h
                                      :target (THREE/WebGLRenderTarget. w h)}
                        :scene scene
                        :world [(make-instance! geometry 0xaaaa00 -2)
                                (make-instance! geometry 0xaa0000 0)
                                (make-instance! geometry 0x0000aa 2)]})))))

(defn render!
  "Ideally, runs at 60 fps"
  [time-stamp]
  (let [time (/ time-stamp 1000)
        {:keys [:camera
                :destination
                :scene
                :world]
         :as state} @state
        camera (:instance camera)
        render-target (:target destination)
        animation (map-indexed (fn [ndx obj]
                                 (let [speed (inc (* ndx 0.1))
                                       rot (* time speed)
                                       rotation (.-rotation obj)]
                                   (set! (.-x rotation) rot)
                                   (set! (.-y rotation) rot)))
                               world)]
    (dorun animation)
    ;; FIXME: So...where does renderer come from?
    ;; If I'm not careful, I could open myself up to a separate OpenGL
    ;; context per thread.
    ;; That's almost definitely safest, but also quite limiting.
    (.setRenderTarget renderer render-target)
    (.render renderer scene camera)
    (.setRenderTarget renderer nil)
    (.postMessage js/self #js {:frereth/action :frereth/render
                               :frereth/texture render-target}))

  ;; This seems like a check to optimize away. Then again, it also seems
  ;; like something branch prediction should handle
  (when has-animator (js/requestAnimationFrame render!)))
(when has-animator
  (js/requestAnimationFrame render!))

;; It seems like this should include the cookie that arrived with ::forking
;; It should not. We don't have any reason to know about that sort of
;; implementation detail here.
;; It would be nice to not even need to do this much.
;; On the other hand, it would also be really nice to have some sort of
;; verification that this is what we expected.
;; Then again, that *is* one of the main points behind TLS.
(let [message {:frereth/action :frereth/forked
               :frereth/needs-dom-animation (if has-animator
                                              false  ; Probably no real need to tell it I can animate myself
                                              :frereth/immediate  ; trigger next frame as fast as possible
                                              )}]
  (.postMessage js/self (transit/write (transit/writer :json) message)))
(js/console.log "Worker bottom")
