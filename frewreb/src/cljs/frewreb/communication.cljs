(ns frewreb.communication)

(defn init []
  {:socket (atom nil)})

(defn start
  [system]
  (swap! (:socket system) (fn [previous]
                            (when previous
                              (.log js/console "TODO: Deal with reopening the websocket"))
                            (let [sock (js/WebSocket. "ws://localhost:8091")]
                                 (set! (.-onopen sock) (fn []
                                                         (js/alert "Socket opened!")))
                                 (set! (.-onmessage sock) (fn [msg]
                                                            (js/alert msg)))
                                 sock))))

(defn stop
  [system]
  (reset! (:socket system) nil))
