(ns frereth.apps.roadblocks.worker
  "Entry point for Web Worker to do real work"
  ;; Kicker about this: it probably needs a System more
  ;; than core does
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

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
(s/def ::camera (s/keys :req [::fov
                              ::aspect
                              ::near
                              ::far
                              ::ui/camera]))

(s/def ::destination (s/merge ::ui/dimensions-2
                              (s/keys :req [::ui/renderer])))
(s/def ::world (s/coll-of ::ui/mesh))

(s/def ::state (s/keys :req [::camera
                             ::destination
                             ::ui/scene
                             ::world]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)
(js/console.log "Worker top")

(defn create-initial-state
  []
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

;; feels wrong to make these "global."
;; But it also doesn't seem worth defining its own system, yet.
(defonce state (atom (create-initial-state)))
;; Q: How much sense does it make for this to be a def vs. defonce?
;; A: Since this implementation is purely a throw-away PoC, who cares
;; either way?
(def clock (atom 0))
;; Can this animate itself?
;; Currently, in Firefox, at least, the main thread has to trigger each
;; frame.
(defonce has-animator
  (boolean js/requestAnimationFrame))

(defmulti handle-incoming-message!
  "Cope with incoming messages"
  (fn [action _]
    action))

(defprotocol EventTarget
  "Wrapper around js/EventTarget"
  (^:export addEventListener
   ;; Options parameter might be [deprecated] boolean useCapture
   [this type-name listener options]
   "Add listener for event dispatching")
  (^:export removeEventListener
   [this type listener options]
   "Options parameter might be [deprecated] boolean useCapture")
  (^:export dispatchEvent
   [this event]
   "Returns false if event is cancelable and at least one handler called .preventDefault otherwise true"))

(defrecord BogusCanvas
    []
  EventTarget
  (^:export addEventListener
    ;; Q: Why can't I handle multiple arities?
    #_#_([this type-name listener]
     :anything)
    ([this type-name listener options]
     :non-falsey)
    [this type-name listener options]
    ;; Q: Can I do anything that makes sense here?
    nil)
  #_(removeEventListener
      [type-name listener options])
  (dispatchEvent
    [this event]
    true))

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
            ;; This isn't working, even with simple compilation.
            ;; Q: Why not?
            canvas #_:something #_(BogusCanvas.) #js {:addEventListener (fn [type-name listener options]
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
            ;; Q: How does this work?
            ;; If I'm not careful, I could open myself up to a separate OpenGL
            ;; context per thread.
            ;; That seems safest, but it's actually quite limiting:
            ;; on my current desktop, I'm limited to 16 contexts.
            ;; (Yes, I have an ancient video card)
            ;; Next problem: this is set up to require a DOM canvas.
            ;; Which takes us back to the original off-screen
            ;; shared canvas example that will not work in FireFox.
            ;; Well, not without major hoops to tackle.
            ;; Try supplying something like a bogus canvas here
            renderer (THREE/WebGLRenderer. #js {:canvas #_canvas (clj->js canvas)})
            render-target (THREE/WebGLRenderTarget. w h)]
        (.setRenderTarget renderer render-target)
        (swap! state into {::camera {::fov fov
                                     ::aspect aspect
                                     ::near near
                                     ::far far
                                     ::ui/camera camera}
                           ::destination {::ui/width w
                                          ::ui/height h
                                          ::ui/renderer renderer
                                          ::ui/render-target render-target}
                           ::ui/scene scene
                           ::world cubes})))))
;; It's important that this gets called before
;; render-and-animate!
;; It configures global state that the latter uses
(define-world!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn render-and-animate!
  "This triggers all the interesting pieces"
  [renderer time-stamp]
  (let [time (/ time-stamp 1000)
        {:keys [::ui/camera
                ::destination
                ::ui/scene
                :world]
         :as state} @state
        camera (::ui/camera camera)
        renderer (::ui/renderer destination)
        render-target (::ui/render-target destination)
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
           "Trying to set the render-target to" render-target
           "on renderer" renderer
           "a" (type renderer))
    (try
      #_(.setRenderTarget renderer render-target)
      (.render renderer scene camera)
      #_(.setRenderTarget renderer nil)
      (catch :default ex
        (.error js/console ex
                "[WORKER] Trying to use"
                renderer
                "to render"
                scene)
        (throw ex)))

    ;; FIXME: Need a wrapper for this.
    ;; Don't send raw messages willy-nilly.
    ;; Need to update the Lamport clock.
    (.postMessage js/self (serial/serialize {:frereth/action :frereth/render
                                             :frereth/texture (.-texture render-target)})))

  ;; This seems like a check to optimize away. Then again, it also seems
  ;; like something branch prediction should handle, and premature
  ;; optimization etc.
  (when has-animator
    (js/requestAnimationFrame (partial render-and-animate! renderer))))
(when has-animator
  (let [renderer (-> state deref ::destination ::ui/renderer)]
    (js/requestAnimationFrame (partial render-and-animate! renderer))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Event handlers

(set! (.-onerror js/self)
      (fn
        [error]
        (.info js/console
               "[WORKER] Error:" error
               "'exception' type:" (type error))
        ;; We can call .preventDefault on error to "prevent the default
        ;; action from taking place."
        ;; Q: Do we want to?
        (comment (.preventDefault error))))

(defmethod handle-incoming-message! :frereth/disconnect
  [_ _]
  ;; Q: This really should cope with connection drops. Need
  ;; to be able to gracefully recover from those
  (throw (js/Error. "Not Implemented")))

(defmethod handle-incoming-message! :frereth/halt
  [_ _]
  (.log js/console "World halted. Exiting.")
  ;; This method has been deprecated.
  ;; Q: What is there anything to do instead?
  (when-let [close (.-close js/self)]
    (close))
  ;; A: Well, this is a bare minimum

  ;; Q: Does/should this get called when there's a
  ;; hot code reload due to code changes?
  ;; That seems like a mistake, but the docs claim
  ;; that the worst downside is that the related resources have to be
  ;; set back up on the graphics hardware.
  ;; So a little extra delay.
  ;; And, really, it would be worse to leave old garbage just sitting
  ;; on there.
  (let [{:keys [::destination
                ::ui/scene
                ::world]
         :as current} @state]
    (let [{:keys [::ui/renderer]} destination]
      (.dispose destination))
    (doseq [mesh world]
      (.dispose (.-geometry mesh))
      (.dispose (.-map (.-material mesh))))
    (.dispose scene))
  (reset! state (create-initial-state)))

(defmethod handle-incoming-message!   :frereth/event
  ;; UI event
  [_ {[tag ctrl-id event] :frereth/body} data]
  (.log js/console "Should dispatch" event "to" ctrl-id "based on" tag)
  (try
    (.error js/console "What does this mean in this context?")
    (catch :default ex
      (.error js/console ex))))
(throw (ex-info "Missing"
                {:need ["Handlers for messages from core"
                        "Animation-frame is obvious"
                        "Proxy events for orbit controls"]}))

(defmethod handle-incoming-message! :frereth/forward
  ;; Data passed-through from server
  [_ data]
  (.warn js/console "Worker should handle" data))

(defmethod handle-incoming-message! :frereth/resize
  [_ {:keys [::ui/width
             ::ui/height]
      :as new-dims}]
  (.error js/console "Wrap :frereth/resize as :frereth/event")
  (throw (js/Error. "This should be a :frereth/event"))
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
  ;; Q: What does the ^js metadata do?
  [^js message-wrapper]
  (.log js/console "Worker received event" message-wrapper)
  (let [{:keys [:frereth/action]
         remote-clock ::lamport/clock
         :as data} (serial/deserialize (.-data message-wrapper))]
    (if remote-clock
      (lamport/do-tick clock remote-clock)
      (do
        (.warn js/console "Incoming payload missing clock tick")
        (lamport/do-tick clock)))
    (handle-incoming-message! action data)))

(defn init
  []
  (.log js/console "Background worker: init")
  ;; The shadow-cljs example shows this as
  (js/self.addEventListener "message" message-handler)
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
  (js/console.log "Worker bottom"))
