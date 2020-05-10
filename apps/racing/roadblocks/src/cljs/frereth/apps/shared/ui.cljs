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

;; It would probably make a lot more sense for this to
;; be a wrapper around the low-level camera, like what
;; I have in worker.cljs
(s/def ::camera #(instance? THREE/Camera %))
(s/def ::curve #(instance? THREE/Curve %))
(s/def ::direction #(instance? THREE/Vecter3 %))
(s/def ::group #(instance? THREE/Group %))
(s/def ::mesh #(instance? THREE/Mesh %))
(s/def ::object-3d #(instance? THREE/Object3D %))
(s/def ::renderer #(instance? THREE/WebGLRenderer %))
(s/def ::render-target #(instance? THREE/WebGLRenderTarget %))
(s/def ::scene #(instance? THREE/Scene %))
;; Probably shouldn't use this directly: want to be
;; able to swap out loading styles seamlessly.
;; This gets problematic in terms of async handling.
;; The current approach is bad, but simple.
(s/def ::texture-loader #(instance? THREE/TextureLoader %))
(s/def ::vector #(instance? THREE/Vector3 %))

;; Since these are based on ::vector, I have to define them
;; after it.
(s/def ::forward-vector ::vector)
(s/def ::position ::vector)
(s/def ::up-vector ::vector)

(s/def ::child (s/keys :req [::object-3d]))
(s/def ::children (s/coll-of ::child))

(s/def ::time-stamp (s/and number? (complement neg?)))

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
  {:pre [renderer]}
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
