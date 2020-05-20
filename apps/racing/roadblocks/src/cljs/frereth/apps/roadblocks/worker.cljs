(ns frereth.apps.roadblocks.worker
  "Entry point for Web Worker to do real work"
  ;; Kicker about this: it probably needs a System more
  ;; than core does
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.roadblocks.game.runners :as runners]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.specs :as specs]
   [frereth.apps.shared.worker :as shared-worker]
   [frereth.apps.shared.ui :as ui]
   ["three" :as THREE]))

(enable-console-print!)
(js/console.log "Worker top")

;; I really want to add a REPL connection into this web worker.
;; FIXME: Figure out how

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::destination (s/merge ::ui/dimensions-2
                              (s/keys :req [::ui/renderer])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; Q: How much sense does it make for this to be a def vs. defonce?
;; A: Since this implementation is purely a throw-away PoC, who cares
;; either way?
(def global-clock (atom 0))

;; Can this animate itself?
;; Currently, in Firefox, at least, the main thread has to trigger each
;; frame.
(defonce has-animator?
  (boolean (exists? js/requestAnimationFrame)))

(def destination (atom {::ui/width 512
                        ::ui/height 512
                        ::ui/renderer nil}))

(defmulti handle-incoming-message!
  "Cope with incoming messages"
  (fn [action _]
    action))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef render-and-animate!
  :args (s/cat :clock ::lamport/clock
               :canvas ::ui/canvas
               :renderer ::ui/renderer
               :time-stamp ::specs/time-stamp)
  :ret any?)
(defn render-and-animate!
  [clock canvas renderer time-stamp]
  (let [{:keys [::ui/camera]
         :as camera-wrapper} (::runners/camera @runners/state-atom)
        scene (runners/do-physics (/ time-stamp 1000))]
    (try
      (.render renderer scene camera)
      (catch :default ex
        (.error js/console "Failed to render" scene
                "on" renderer
                "from" camera)
        (throw ex)))
    (let [img-bmp (.transferToImageBitmap canvas)]
      ;; Here's an ugly piece where the abstraction leaks.
      ;; I don't want this layer to know anything about underlying details
      ;; like the clock.
      ;; Its usage is scattered all over the place in here anyway.
      ;; TODO: Invest some hammock time into hiding this detail.
      (shared-worker/transfer-to-worker! clock
                                         js/self
                                         :frereth/render
                                         img-bmp))
    ;; When I uncomment this line, the scene getting BLT'd to the
    ;; spinning cube on the window manager goes into spastic animation
    ;; mode. So there's a difference between the way I'm handling
    ;; time-stamp here and the way it's happening from
    ;; utils.previews.frame.
    ;; More importantly, for my immediate purposes, there's a slew of
    ;; errors about
    ;; INVALID_OPERATION: uniform3f: location not for current program
    ;; and
    ;; INVALID_OPERATION: uniformMatrix4fv: location is not from
    ;; current program
    ;; Nice detail: I can comment this line out, and the animation
    ;; stops.
    ;; Less nice: uncommenting it doesn't re-start the animation
    ;; (of course)

    ;; Q: Is this still true? Or have I gotten around to fixing it?
    (when has-animator?
      (if (or true (> 10 @global-clock))
        (do
          #_(.info js/console "Frame" @global-clock)
          #_(js/setTimeout
             #(js/requestAnimationFrame (partial render-and-animate! clock canvas renderer))
             1000)
          (js/requestAnimationFrame (partial render-and-animate!
                                             clock canvas renderer))
          (swap! global-clock inc))
        (.info js/console "1 frame down. Stopping. Renderer:" renderer)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Event handlers

(set! (.-onerror js/self)
      (fn
        [error]
        (.error js/console
                "[WORKER] Error:" error
                "'exception' type:" (type error))
        (when (exists? (.-stack error))
          (.error js/console (.-stack error)))
        ;; We can call .preventDefault on error to "prevent the default
        ;; action from taking place."
        ;; Q: Do we want to?
        (comment (.preventDefault error))))

(defmethod handle-incoming-message! :frereth/disconnect
  [_ _]
  ;; Q: This really should cope with connection drops. Need
  ;; to be able to gracefully recover from those
  (throw (js/Error. "TODO: Cope with web server disconnect.")))

(defmethod handle-incoming-message!   :frereth/event
  ;; UI event. This is where my event handling Proxy
  ;; should/will come into play
  [_ {[tag ctrl-id event] :frereth/body} data]
  (.log js/console "Should dispatch" event "to" ctrl-id "based on" tag)
  (try
    (.error js/console "What does this mean in this context?")
    (catch :default ex
      (.error js/console ex))))

(defmethod handle-incoming-message! :frereth/forward
  ;; Data passed-through from server to...well, something
  ;; in this world.
  ;; Obviously, this needs more thought.
  [_ data]
  (.warn js/console "Worker should handle" data))

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
  (let [{:keys [::ui/renderer]} @destination]
    (.dispose destination)))

(defmethod handle-incoming-message! :frereth/main
  [_
   {canvas :frereth/message
    :as body}]
  (.info js/console
         "handle-incoming-message! :frereth/main" canvas
         "\nin" body)
  ;; Q: How does this actually work?
  ;; A: Pretty sure it produces a separate OpenGL context per
  ;; thread.
  ;; That seems safest, but it's actually quite limiting:
  ;; on my current desktop, I'm limited to 16 contexts.
  ;; (Yes, I have an ancient video card)
  ;; To implement this approach, we really need an
  ;; OffscreenCanvas.
  ;; That means giving up on support for things like Firefox
  ;; and iOS (well, maybe...according to google, you can
  ;; install chrome from the app store now)
  ;; Supplying something a bogus canvas here acts like it might
  ;; work.
  ;; Long-term, I think I want this to produce a js/Proxy that can
  ;; feed back the functions with which it got called to replay
  ;; on the main thread.
  ;; That needs to be heavily vetted and raises all sorts of
  ;; red flags about breaking the encapsulation I'm trying to
  ;; establish here.
  ;; So it isn't something to just automate away.
  (let [renderer (THREE/WebGLRenderer. #js{:canvas canvas :alpha true})]
    (swap! destination assoc ::ui/renderer renderer)
    (runners/define-world!)
    (render-and-animate! global-clock
                         canvas
                         renderer
                         1)))

(defmethod handle-incoming-message! :frereth/resize
  [_ {:keys [::ui/width
             ::ui/height]
      :as new-dims}]
  (.error js/console "Wrap :frereth/resize as :frereth/event")
  (throw (js/Error. "This should be a :frereth/event"))
  (let [render-dst @destination
        current-dims (select-keys render-dst [::ui/width
                                              ::ui/height])]
    (when (ui/should-resize-renderer? current-dims new-dims)
      (runners/resize! new-dims)
      (swap! destination
             (fn [dst]
               (assoc dst
                      ::ui/width width
                      ::ui/height height))))))

(s/fdef message-handler
  ;; FIXME: Message wrapper spec
  :args (s/cat :wrapper any?)
  ;; Called for side-effects
  :ret any?)
(defn message-handler
  "Unwrap and dispatch incoming messages"
  ;; Q: What does the ^js metadata do?
  [^js message-wrapper]
  (.log js/console "Worker received event" message-wrapper)
  (let [{message-type :type
         remote-clock :clock
         body :body
         :as envelope} (js->clj (.-data message-wrapper) :keywordize-keys true)]
    (if remote-clock
      (lamport/do-tick global-clock remote-clock)
      (do
        (.warn js/console
               "Incoming payload missing clock tick"
               (clj->js (keys envelope))
               "among"
               envelope)
        (lamport/do-tick global-clock)))
    (let [{:keys [:frereth/action]
           :as data} (condp = message-type
                       "cloned" (serial/deserialize body)
                       "raw" {:frereth/action (keyword "frereth" (:action envelope))
                              :frereth/message body})]
      (handle-incoming-message! action data))))

(defn main
  []
  (.log js/console "Background worker: init")
  ;; The shadow-cljs example shows this as
  #_(js/self.addEventListener "message" message-handler)
  (set! (.-onmessage js/self) message-handler)

  ;; It seems like this should include the cookie that arrived with ::forking
  ;; It should not. We don't have any reason to know about that sort of
  ;; implementation detail here.
  ;; It would be nice to not even need to do this much.
  ;; On the other hand, it would also be really nice to have some sort of
  ;; verification that this is what we expected.
  ;; Then again, that *is* one of the main points behind TLS.
  (let [message {:frereth/action :frereth/forked
                 :frereth/needs-dom-animation? (if has-animator?
                                                 false  ; Probably no real need to tell it I can animate myself
                                                 :frereth/immediate  ; trigger next frame as fast as possible
                                                 )}]
    (.postMessage js/self (serial/serialize message)))
  (if true
    ;; Punt on these for now. I want to paint some pixels.
    (.warn js/console "TODO: Implement missing handlers")
    ;; This shows up in the Console as [object Object] because
    ;; it's escaping to the main thread's error context.
    ;; Since there isn't anything to handle it here, of course
    ;; it should. But it's annoyingly opaque.
    ;; Actually, it also shows up near the bottom of the logs with
    ;; appropriate context. So that's better than I expected at first.
    ;; All these handlers (or, at least, a proxy for them) are
    ;; important for adding things like Orbit controls.
    ;; But I don't really care in terms of the initial demo/proof
    ;; of concept for minimalist off-screen rendering.
    (throw (ex-info "Missing"
                    {:need ["Handlers for messages from core"
                            "Animation-frame is obvious"
                            "Proxy events for orbit controls"]})))
  (js/console.log "Worker bottom"))
