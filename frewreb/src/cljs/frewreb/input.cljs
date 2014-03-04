(ns frewreb.input
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [enfocu.macros :as em])
  (:require [cljs.core.async :as async]
            [enfocus.events :as events]))

(defn init
  []
  {:renderer-> (atom nil)})

(defn start 
  [system]
  (let [c-> (async/chan)]
    (reset! (:renderer-> system) c->)
    (em/defaction event [msg]
      (go (async/>! c-> msg)))
    (em/defaction setup []
      ["#reality"] (events/listen :click #(event [:click :where :make-this-useful]))
      )
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
