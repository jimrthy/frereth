;;; If this namespace requires macros, remember that ClojureScript's
;;; macros are written in Clojure and have to be referenced via the
;;; :require-macros directive where the :as keyword is required, while in Clojure is optional. Even
;;; if you can add files containing macros and compile-time only
;;; functions in the :source-paths setting of the :builds, it is
;;; strongly suggested to add them to the leiningen :source-paths.
(ns frewreb.core)

(defn draw
  "This should be the interesting part. Except that, really, it isn't."
  [gl buffers]
  (let [width  (.-viewportWidth gl)
        height (.-viewportHeight gl)]
    (.viewport gl 0 0 width height)
    (.clear gl (bit-or (.-COLOR_BUFFER_BIT gl) (.-DEPTH_BUFFER_BIT gl)))

    ;; TODO: Lots of undefined pieces here
    (.perspective js/mat4 45 (/ width height) 0.1 100.0 perspective-matrix)
    (.identity js/mat4 model-view-matrix)

    ;;; TODO: Really should just loop over the objects that were
    ;;; provided as parameters. This function is far too interesting.
    ;;; That means that the objects need to be smarter.
    ;; The triangle
    (.translate js/mat4 model-view-matrix [-1.5 0.0 -7.0])
    (let [b (buffer 0)]
      (.bindBuffer gl (.-ARRAY_BUFFER gl) b)
      (.vertexAttribPointer gl (.-vertexPositionAttribute shader-program)
                            (.-itemSize b) (.-FLOAT gl) false 0 0)
      (set-matrix-uniforms)
      (.drawArrays gl (.-TRIANGLES gl) 0 (.-numItems b)))

    (.translate js/mat4 model-view-matrix [3.0 0.0 0.0])
    (let [b (buffer 1)]
      (.bindBuffer gl (.-ARRAY_BUFFER gl) b)
      (.vertexAttribPointer gl (.-vertexPositionAttribute shader-program)
                            (.-itemSize b) (.-FLOAT gl) false 0 0)
      (set-matrix-uniforms)
      (.drawArrays gl (.-TRIANGLE_STRIP gl) 0 (.-numItems b)))))

(defn build-triangle-buffer 
  "Returns a triangle that has its vertices pushed to the graphics card"
  [gl]
  (let [triangle-vertex-position-buffer (.createBuffer gl)]
    (.bindBuffer gl (.-ARRAY_BUFFER gl) triangle-vertex-position-buffer)
    (let [vertices [ 0.0  1.0 0.0
                    -1.0 -1.0 0.0
                    1.0 -1.0 0.0]]
      (.bufferData gl (.-ARRAY_BUFFER gl) (Float32Array. vertices)
                   (.-STATIC_DRAW gl)))
    (set! (.-itemSize triangle-vertex-position-buffer) 3)
    (set! (.-numItems triangle-vertex-position-buffer) 3)
    triangle-vertex-position-buffer))

(defn build-square-buffer
  "Returns a triangle that has its vertices pushed to the graphics card"
  [gl]
  (let [square-vertex-position-buffer (.createBuffer gl)]
    (.bindBuffer gl (.-ARRAY_BUFFER gl) square-vertex-position-buffer)
    (let [vertices [ 1.0  1.0  0.0
                    -1.0  1.0  0.0
                     1.0 -1.0  0.0
                    -1.0 -1.0  0.0]]
      (.bufferData gl (.-ARRAY_BUFFER gl) (Float32Array. vertices) (.-STATIC_DRAW gl))
      (set! (.-itemSize square-vertex-position-buffer) 3)
      (set! (.-numItems square-vertex-position-buffer) 4)
      square-vertex-position-buffer)))

(defn init-buffers [gl]
  (let [triangle (build-triangle-buffer gl)
        square (build-square-buffer gl)]
    [triangle square]))

(defn start []
  (let [canvas (.getElementById js/document "reality")]
    (let [gl (init-gl canvas)]
      (init-shaders)
      (let [buffers (init-buffers gl)]

        (.clearColor gl 0.0 0.0 0.0 1.0)
        (.enable gl (.-DEPTH_TEST gl))
        (draw gl buffers)))))

(set! (.-onload js/window) start)
