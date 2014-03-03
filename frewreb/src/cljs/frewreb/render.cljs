;;; If this namespace requires macros, remember that ClojureScript's
;;; macros are written in Clojure and have to be referenced via the
;;; :require-macros directive where the :as keyword is required, while in Clojure is optional. Even
;;; if you can add files containing macros and compile-time only
;;; functions in the :source-paths setting of the :builds, it is
;;; strongly suggested to add them to the leiningen :source-paths.
(ns frewreb.render
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs-webgl.context :as context]
            [cljs-webgl.shaders :as shaders]
            [cljs-webgl.constants :as constants]
            [cljs-webgl.buffers :as buffers]
            [cljs-webgl.typed-arrays :as ta]
            [cljs.core.async :as async]))

(defn draw-default
  [system gl shader vertex-buffer element-buffer triangle-count]
  (buffers/clear-color-buffer gl 0 0 0 1)
  (let [frame @(:frame system)]
    (buffers/draw! gl
                   shader
                   {:buffer vertex-buffer
                    :attrib-array (shaders/get-attrib-location gl
                                                               shader
                                                               "vertex_position")
                    :mode constants/triangles
                    :first 0
                    :count (* triangle-count 3)
                    :components-per-vertex 3
                    :type constants/float
                    :normalized? false
                    :stride 0
                    :offset 0}
                   [{:name "frame" :type :int :values [frame]}]
                   {:buffer element-buffer
                    :count 3
                    :type constants/unsigned-short
                    :offset 0})
    system))

(let [vert-shader-src
      "attribute vec3 vertex_position;
void main() {
  gl_Position = vec4(vertex_position, 1);
}"
      gl (context/get-context (.getElementById js/document "reality"))
      element-buffer (buffers/create-buffer gl (ta/unsigned-int16 [0 1 2])
                                            constants/element-array-buffer
                                            constants/static-draw)
      vert-shader (shaders/create-shader gl constants/vertex-shader vert-shader-src)]

  (defn draw-init
    [system]
    (let [frag-shader-src
          "void main() {
  gl_FragColor.r = 0.25; gl_FragColor.g = 0.25; gl_FragColor.b = 0.25;
  gl_FragColor.a = 1.0;
}"
          shader (shaders/create-program gl [vert-shader
                                             (shaders/create-shader gl constants/fragment-shader frag-shader-src)])
          vertex-buffer (buffers/create-buffer gl (ta/float32 [-1.0 1.0 0.0
                                                               -1.0 -1.0 0.0
                                                               1.0 0.0 0.0])
                                               constants/array-buffer
                                               constants/static-draw)]
      (draw-default system gl shader vertex-buffer element-buffer 1)))

  (defn draw-splash 
    [system]
    (let
        [fragment-shader-source
         "uniform int frame;
void main() {
  gl_FragColor.r = sin(float(frame) * 0.05) / 2.0 + 0.5;
  gl_FragColor.g = sin(float(frame) * 0.1) / 2.0 + 0.5;
  gl_FragColor.b = sin(float(frame) * 0.02) / 2.0 + 0.5;
  gl_FragColor.a = 1.0;
}"
         shader (shaders/create-program gl [vert-shader
                                            (shaders/create-shader gl constants/fragment-shader fragment-shader-source)])
         vertex-buffer (buffers/create-buffer gl (ta/float32 [1.0 1.0 0.0
                                                              -1.0 1.0 0.0
                                                              1.0 -1.0 0.0])
                                              constants/array-buffer
                                              constants/static-draw)]
      (draw-default system gl shader vertex-buffer element-buffer 1)))

  (defn draw-finished
    [system]
    (let [frag-shader-src
          "void main() {
  gl_FragColor.r = 0.25; gl_FragColor.g = 0.25; gl_FragColor.b = 0.25;
  gl_FragColor.a = 1.0;
}"
          shader (shaders/create-program gl [vert-shader
                                             (shaders/create-shader gl constants/fragment-shader frag-shader-src)])
          vertex-buffer (buffers/create-buffer gl (ta/float32 [0.0 1.0 0.0
                                                               -1.0 -1.0 0.0
                                                               1.0 -1.0 0.0])
                                               constants/array-buffer
                                               constants/static-draw)]
      (draw-default system gl shader vertex-buffer element-buffer 1))))

(defn draw [system]
  (let [state @(:state system)
        frame @(:frame system)
        debug-message (println-str "Drawing frame # " frame " in State " state)]
    (comment (.log js/console debug-message))
    (if-let [update (case state
                      :initialized (draw-init system)
                      :started (draw-splash system)
                      :stopped (draw-finished system)
                      nil)]
      update
      (let [msg (println-str "Unknown state: " state)]
        (.log js/console msg)
        system))))

(defn main-loop [system continue]
  (let [update-in-sys (fn [k v]
                        (reset! (k system) v)
                        system)
        handle-command (fn [msg]
                         (if msg
                           (case msg
                             :exit ((update-in-sys :done true))
                             :pause (update-in-sys :paused true)
                             :unpause (update-in-sys :paused false)
                             :broken)
                           system))]
    ;; OpenGL really wants the drawing to happen in the main thread.
    ;; Since javascript is single-threaded, that really isn't a factor
    (go
     (let [done @(:done system)
           paused @(:paused system)]
       (when-not done
         ;; Draw first
         (when-not paused
           (draw system)
           (swap! (:frame system) inc))
         (.requestAnimationFrame js/window (fn [time-elapsed]
                                             (continue system continue)))
         ;; Check for updates.
         ;; I'd actually rather do this before drawing, but that approach
         ;; leaves me with lots of duplicate code.
         (let [command-channel @(:->renderer system)
               ;; Really don't want to block
               alt (async/timeout 1)
               [v c] (async/alts! [command-channel alt])]
           (if (= c command-channel)
             (handle-command v)
             system)))))))

(defn init
  []
  {:->renderer (atom nil)
   :renderer-> (atom nil)
   :state (atom :initialized)
   :frame (atom 0)
   ;; It's tempting to try to make these fit into :state as an FSM.
   ;; That temptation seems horribly misguided.
   :done (atom false)
   :paused (atom false)})

(defn start [system]
  (let [->c (async/chan)
        c-> (async/chan)]
    (reset! (:->renderer system) ->c)
    (reset! (:renderer-> system) c->)
    (reset! (:state system) :started)
    (.requestAnimationFrame js/window (fn [time-elapsed] (main-loop system main-loop)))
    system))

(defn stop [system]
  (reset! (:done system) true)
  (let [channel-atom (:->renderer system)]
    ;; This is an input channel. The other side has to close.
    (comment (if-let [chan @channel-atom]
               (do
                 (async/close! chan))
               (js/alert "No channel for renderer to close")))
    (reset! channel-atom nil))
  (if-let [out-channel @(:renderer-> system)]
    (async/close! out-channel)
    (js/alert "No output channel for renderer to close"))
  (reset! (:state system) :stopped)
  system)
