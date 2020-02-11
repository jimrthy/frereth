(ns roadblocks.core
  "This namespace contains your application and is the entrypoint for 'yarn start'."
  (:require
   [frereth.apps.shared.worker :as worker]
   ["three" :as THREE]))

(defn ^:dev/after-load render
  "Render the toplevel component for this app."
  []
  (let [canvas (.querySelector "root")
        renderer (THREE/WebGLRenderer. #js {"antialias" true})]))

(defn ^:export main
  "Run application startup logic."
  []
  (render))
