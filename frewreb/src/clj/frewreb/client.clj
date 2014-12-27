(ns frewreb.client
  (:require [cljeromq.core :as mq]))

(defn init
  []
  {:->renderer (atom nil)
   :dispatcher (atom nil)})

(defn hand-shake
  [_]
  ;; This approach really doesn't work at all.
  ;; Honestly need a new socket for each renderer
  ;; socket we receive.
  ;; Or, at least, a new connection on the socket.
  (let [ctx (mq/context)
        sock (mq/socket ctx :router)
        connected? (atom false)]
    (async/thread
     ;; FIXME: Don't hard-code anything!
     (mq/connect! sock "tcp://127.0.0.1:8902")
     (mq/send sock {:ohai {:version [0 0 1]
                           :protocol :frereth}})
     (let [response (mq/recv sock)]
       ;; Now I need logging
       (print response))
     (reset! connected? true))
    {:context ctx
     :socket sock
     :connected? connected?}))

(defn start
  [dead-system socket-router]
  (let [->renderer @(:->renderer socket-router)
        renderer-> @(:renderer-> socket-router)
        dispatcher-atom (:dispatcher dead-system)]
    (reset! (:->renderer dead-system ->renderer))

    (swap! dispatcher-atom hand-shake)
    (let [dispatcher @dispatcher-atom
          connected? (:connected? dispatcher)
          sock (:socket dispatcher)]
      ;; Pass along messages from the client
      ;; Really don't want to do this until after we've
      ;; exchanged a handshake with the server
      (go
       (loop [msg (async/<! renderer->)]
         (if connected?
           (do
             (mq/send sock msg))
           (async/>! ->renderer "Connecting to server..."))
         (recur (async/<! renderer->))))

      ;; And vice versa
      (go
       ;; I'm making these sorts of loops often enough
       ;; that it almost seems worth writing a macro.
       ;; Though, honestly, a higher order function
       ;; could handle it.
       (loop [msg (mq/recv sock)]
         (when msg
           (async/>! ->renderer msg)
           (recur (mq/recv sock)))))

      dead-system)))

(defn stop
  [live]
  ;; It's more than a little weird to have this
  ;; channel created elsewhere, but controlled
  ;; (and killed) from here.
  ;; But we really have a mutual dependency:
  ;; both namespaces need both channels to talk
  ;; back and forth. So this seemed like the
  ;; most straightforward way to handle that conundrum.
  (let [->renderer @(:->renderer live)
        dispatcher @(:dispatcher live)
        sock (:socket dispatcher)
        ctx (:context dispatcher)]
    (async/close! ->renderer)
    ;; TODO: Ensure that linger has been set to 0
    (mq/close! sock)
    (mq/terminate! ctx))
  (init))
