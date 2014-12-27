(ns frewreb.input
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [enfocus.macros :as em])
  (:require [cljs.core.async :as async]
            [enfocus.events :as events]))

(defn init
  []
  {:renderer-> (atom nil)})

(defn start 
  [system]
  (let [c-> (async/chan)]
    (reset! (:renderer-> system) c->)
    (let [event (fn [which args]
                  (go (async/>! c-> [which args])))]
      (em/defaction setup []
        ["#reality"]
        (let [listener (fn [which e]
                         (event which e))
              ;; This seems more than a little silly. Are there
              ;; any other events that make sense? Maybe a timer?
              events [:click :dblclick :mousedown :mouseup :mouseout
                      :mouseenter :mouseleave :mousemove
                      :keypress :keydown :keyup
                      :blur :focus
                      :resize]]
          (dorun (map listener events)))))
    (set! (.-onload js/window) setup))  
  system)

(defn stop
  [system]
  (if-let [c-atom (:renderer-> system)]
    (if-let [c-> @c-atom]
      (do (async/close! c->)
          (reset! c-atom nil))
      (.error js/console "No output channel for the input"))
    (.error js/console "Missing the atom that should hold the input's output channel"))
  system)
