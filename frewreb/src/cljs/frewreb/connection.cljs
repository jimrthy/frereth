(ns frewreb.connection
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async]))

(defn shake-hands
  [sock client-> ->renderer]
  (.log js/console "Initiating handshake")
  (go
   (let [connect (async/<! client->)]
     ;; TODO: This really needs some sort of expects scripting
     (if (= connect :opened)
       (do
         (.send sock (pr-str {:ohai {:version [0 0 1]
                                     :protocal :frereth}}))
         (if-let [response (async/<! client->)]
           (do (.log js/console (println-str "Client has shaken hands: " response))
               (async/>! ->renderer response)
               {:->renderer ->renderer})
           (do (.error js/console ("Client socket closed unexpectedly. This is pretty fatal"))
               false)))
       (.error js/console (println-str "Bad first message from client: " connect))))))

(defn init []
  {:connection (atom nil)})

(defn dispatch
  "FIXME: This needs to be smarter.
Some messages from the client will really be for here
 (e.g. a session's timed out and we need to revalidate)"
  [chan msg]
  (async/>!! chan msg))

(defn start [system]
  (if-let [comm (:communications system)]
    (if-let [<->client-atom (:socket comm)]
      (if-let [client->-atom (:client-> comm)]
        (let [draw (:renderer system)]
          (if-let [->renderer-atom (:->renderer draw)]
            (let [<->client @<->client-atom
                  client-> @client->-atom
                  ->renderer @->renderer-atom
                  connection-block (shake-hands <->client client-> ->renderer)]

              ;; Since cljs doesn't seem to support any sort of synchronous
              ;; channel ops, update the atom this way.
              (async/take! connection-block (fn [connection]
                                              (.log js/console "Handshake complete")
                                              (reset! (:connection system) connection)))

              ;; Messages from the client
              (go
               (loop [msg (async/<! client->)]
                 (when msg
                   (dispatch ->renderer msg)
                   (recur (async/<! client->))))))
            (do
              (.error js/console "No atom for the user interface output Channel"))))
        (do
          (.error js/console "No atom for the async channel reporting Web Socket messages from the Client")))
      (do
        (let [msg (println-str "No atom for the Web Socket:\n" (pr-str comm)
                               "\n" )]
          (.error js/console msg))))
    (do
      (.error js/console "Trying to initiate a Connection before Communications")))
  system)

(defn stop [system]
  (let [connection @(:connection system)
        ->renderer (:->renderer connection)]
    (async/close! ->renderer)))
