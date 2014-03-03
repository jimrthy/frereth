;;; If this namespace requires macros, remember that ClojureScript's
;;; macros are written in Clojure and have to be referenced via the
;;; :require-macros directive where the :as keyword is required, while in Clojure is optional. Even
;;; if you can add files containing macros and compile-time only
;;; functions in the :source-paths setting of the :builds, it is
;;; strongly suggested to add them to the leiningen :source-paths.
(ns frewreb.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs-webgl.context :as context]
            [cljs-webgl.shaders :as shaders]
            [cljs-webgl.constants :as constants]
            [cljs-webgl.buffers :as buffers]
            [cljs-webgl.typed-arrays :as ta]
            [cljs.core.async :as async]))

(def vertex-shader-source
  "attribute vec3 vertex_position;
void main() {
  gl_Position = vec4(vertex_position, 1);
}")

(def fragment-shader-source
  "uniform int frame;
void main() {
  gl_FragColor.r = sin(float(frame) * 0.05) / 2.0 + 0.5;
  gl_FragColor.g = sin(float(frame) * 0.1) / 2.0 + 0.5;
  gl_FragColor.b = sin(float(frame) * 0.02) / 2.0 + 0.5;
  gl_FragColor.a = 1.0;
}")

(let 
    [gl (context/get-context (.getElementById js/document "reality"))
     shader (shaders/create-program gl [(shaders/create-shader gl constants/vertex-shader vertex-shader-source)
                                        (shaders/create-shader gl constants/fragment-shader fragment-shader-source)])
     vertex-buffer (buffers/create-buffer gl (ta/float32 [1.0 1.0 0.0
                                                          -1.0 1.0 0.0
                                                          1.0 -1.0 0.0])
                                          constants/array-buffer
                                          constants/static-draw)
     element-buffer (buffers/create-buffer gl (ta/unsigned-int16 [0 1 2])
                                           constants/element-array-buffer
                                           constants/static-draw)]
  (defn draw [[command-channel frame] continue]
    (buffers/clear-color-buffer gl 0 0 0 1)
    
    (let [alt (async/timeout 1)
          [v c] (async/alts!! [command-channel alt])
          quit (atom false)]
      (when (= c command-channel)
        (if (= v "exit")
          (reset! quit true)
          (js/alert v)))
      (when-not @quit
        (buffers/draw! gl
                       shader
                       {:buffer vertex-buffer
                        :attrib-array (shaders/get-attrib-location gl
                                                                   shader
                                                                   "vertex_position")
                        :mode constants/triangles
                        :first 0
                        :count 3
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
        (.requestAnimationFrame js/window (fn [time-elapsed]
                                            (continue [command-channel (inc frame)] continue)))))))

(defn init
  []
  {:channel (atom nil)})

(defn start [system]
  (let [c (async/chan)]
    (.requestAnimationFrame js/window (fn [time-elapsed] (draw [c 0] draw)))
    (reset! (:channel system) c)))

(defn stop [system]
  (let [channel-atom (:channel system)]
    (if-let [chan @channel-atom]
      (do
        (async/>! chan "exit")
        (async/close! chan))
      (js/alert "No channel for renderer to close"))
    (reset! channel-atom nil)))
