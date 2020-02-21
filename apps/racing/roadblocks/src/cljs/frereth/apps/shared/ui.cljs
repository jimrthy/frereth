(ns frereth.apps.shared.ui
  "Utilities for coping w/ UI details"
  (:require [clojure.spec.alpha :as s]
            ["three" :as THREE]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::width (s/and pos?
                      integer?))
(s/def ::height (s/and pos?
                       integer?))

(s/def ::dimensions-2 (s/keys :req [::width ::height]))

(s/def ::camera #(instance? THREE/Camera %))
(s/def ::renderer #(instance? THREE/WebGLRenderTarget %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef fix-camera-aspect!
  :args (s/cat :camera ::camera
               :dimensions ::dimensions-2)
  ;; called for side-effects
  :ret any?)
(defn fix-camera-aspect!
  [camera
   {:keys [::width ::height]}]
  (set! (.-aspect camera) (/ width height))
  (.updateProjectionMatrix camera))

(s/fdef resize-renderer-to-display-size!
  :args (s/cat :renderer ::renderer
               :dimensions ::dimensions-2)
  ;; called for side-effects
  :ret any?)
(defn resize-renderer-to-display-size!
  [renderer {:keys [::width ::height]}]
  (.setSize renderer width height false))

(s/fdef should-resize-renderer?
  :args (s/cat :current ::dimensions-2
               :new ::dimensions-2)
  :ret boolean?)
(defn should-resize-renderer?
  [{current-w ::width
    current-h ::height}
   {new-w ::width
    new-h ::height}]
  (or (not= new-w current-w)
      (not= new-h current-h)))
