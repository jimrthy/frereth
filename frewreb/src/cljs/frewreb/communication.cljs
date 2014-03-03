(ns frewreb.communication
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async]))

(defn init []
  {:socket (atom nil)
   :client-> (atom nil)})

(defn start
  [system]
  (let [c (async/chan)]
    (swap! (:socket system) (fn [previous]
                              (when previous
                                (.log js/console "TODO: Deal with reopening the websocket"))
                              (let [sock (js/WebSocket. "ws://localhost:8091")]
                                (set! (.-onopen sock) (fn []
                                                        (go
                                                         (async/>! c :opened))))
                                (set! (.-onmessage sock) (fn [msg]
                                                           (go
                                                            (async/>! c msg))))
                                sock)))
    (reset! (:client-> system) c)
    system))

(defn stop
  [system]
  (reset! (:socket system) nil)
  system)
