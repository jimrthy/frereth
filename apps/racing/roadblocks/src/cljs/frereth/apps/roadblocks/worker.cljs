(ns frereth.apps.roadblocks.worker
  "Entry point for Web Worker to do real work"
  ;; Kicker about this: it probably needs a System more
  ;; than core does
  (:require
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)
(js/console.log "Worker top")

;; feels wrong to make these "global."
;; But it also doesn't seem worth defining its own system, yet.
(def state (atom {}))
(def clock (atom 0))
(def has-animator
  "Can this animate itself?
  Currently, in Firefox, at least, the main thread has to trigger each
  frame."
  (bool js/requestAnimationFrame))

(defmulti handle-incoming-message!
  "Cope with incoming messages"
  (fn [action _]
    action))

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
                               cube))
            cubes [(make-instance! geometry 0xaaaa00 -2)
                   (make-instance! geometry 0xaa0000 0)
                   (make-instance! geometry 0x0000aa 2)]]
        (swap! state into {::camera {::fov fov
                                     ::aspect aspect
                                     ::near near
                                     ::far far
                                     ::ui/camera camera}
                           ::destination {::ui/width w
                                          ::ui/height h
                                          ;; Q: How does this work?
                                          ;; If I'm not careful, I could open myself up to a separate OpenGL
                                          ;; context per thread.
                                          ;; That seems safest, but it's actually quite limiting:
                                          ;; on my current desktop, I'm limited to 16 contexts.
                                          ;; (Yes, I have an ancient video card)
                                          ::ui/renderer (THREE/WebGLRenderTarget. w h)}
                           ::scene scene
                           ::world cubes})))))
(define-world!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn render-and-animate!
  "This triggers all the interesting pieces"
  [time-stamp]
  (let [time (/ time-stamp 1000)
        {:keys [::camera
                ::destination
                ::scene
                :world]
         :as state} @state
        camera (::ui/camera camera)
        render-target (::ui/renderer destination)
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
    (.setRenderTarget renderer render-target)
    (.render renderer scene camera)
    (.setRenderTarget renderer nil)

    (.postMessage js/self (serial/serialize {:frereth/action :frereth/render
                                             :frereth/texture (.-texture render-target)})))

  ;; This seems like a check to optimize away. Then again, it also seems
  ;; like something branch prediction should handle, and premature
  ;; optimization etc.
  (when has-animator (js/requestAnimationFrame render-and-animate!)))
(when has-animator
  (js/requestAnimationFrame render-and-animate!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Event handlers

(set! (.-onerror js/self)
      (fn
        [error]
        (console.error error)
        ;; We can call .preventDefault on error to "prevent the default
        ;; action from taking place."
        ;; Q: Do we want to?
        (comment (.preventDefault error))))

(defmethod handle-incoming-message! :frereth/disconnect
  [_ _]
  ;; Q: This really should cope with connection drops. Need
  ;; to be able to gracefully recover from those
  (console.log "World disconnected. Exiting.")
  (.close js/self))

(defmethod handle-incoming-message!   :frereth/event
  ;; UI event
  [_ {[tag ctrl-id event] :frereth/body} data]
  (.log js/console "Should dispatch" event "to" ctrl-id "based on" tag)
  (try
    (.error js/console "What does this mean in this context?")
    (catch :default ex
      (console.error ex))))
(throw (ex-info "Missing"
                {:need ["Handlers for messages from core"
                        "Animation-frame is obvious"
                        "Proxy events for orbit controls"]}))

(defmethod handle-incoming-message! :frereth/forward
  ;; Data passed-through from server
  [_ data]
  (console.warn "Worker should handle" data))

(defmethod handle-incoming-message! :frereth/resize
  [_ {:keys [::ui/width
             ::ui/height]
      :as new-dims}]
  (let [render-dst (-> state deref ::destination)
        current-dims (select-keys render-dst [::ui/width
                                              ::ui/height])]
    (when (ui/should-resize-renderer? current-dims new-dims)
      (ui/resize-renderer-to-display-size! (::ui/renderer render-dst)
                                           new-dims)
      (let [camera (-> state deref ::camera ::ui/camera)]
        (ui/fix-camera-aspect! camera new-dims)))
    (swap! state
           (fn [current]
             (-> current
                 (update ::destination
                         (fn [dst]
                           (assoc dst
                                  ::ui/width width
                                  ::ui/height height)))
                 (update ::camera
                         assoc
                         ::aspect (/ width height)))))))

(defn message-handler
  "Unwrap and dispatch incoming messages"
  (fn [message-wrapper]
    (console.log "Worker received event" message-wrapper)
    (let [{:keys [:frereth/action]
           remote-clock ::lamport/clock
           :as data} (serial/deserialize (.-data wrapper))]
      (lamport/do-tick clock remote-clock)
      (handle-incoming-message! action data))))

(set! (.-onmessage js/self) message-handler)

;; It seems like this should include the cookie that arrived with ::forking
;; It should not. We don't have any reason to know about that sort of
;; implementation detail here.
;; It would be nice to not even need to do this much.
;; On the other hand, it would also be really nice to have some sort of
;; verification that this is what we expected.
;; Then again, that *is* one of the main points behind TLS.
(let [message {:frereth/action :frereth/forked
               :frereth/needs-dom-animation? (if has-animator
                                               false  ; Probably no real need to tell it I can animate myself
                                               :frereth/immediate  ; trigger next frame as fast as possible
                                               )}]
  (.postMessage js/self (serial/serialize message)))
(js/console.log "Worker bottom")
