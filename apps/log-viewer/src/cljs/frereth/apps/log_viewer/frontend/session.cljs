(ns frereth.apps.log-viewer.frontend.session
  (:require [cljs.core.async :as async]
            [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.socket :as web-socket]
            [integrant.core :as ig]
            [shared.world :as world])
  (:require-macros [cljs.core.async.macros :as async-macros :refer [go]]))

(s/def ::manager (s/keys :req [::web-socket/sock
                               :frereth/worlds]))

(defmethod ig/init-key ::manager
  [_ opts]
  (atom (into {::web-socket/sock (web-socket/wrapper)
               :frereth/worlds {}}
              opts)))

(defmethod ig/halt-key! ::manager
  [_ {:keys [:frereth/worlds
             ::web-socket/sock]}]
  (when (< 0 (count worlds))
    (let [altered-worlds (atom worlds)]
      (go
        (loop [disconnections (reduce (fn [acc [world-key world]]
                                        (let [ch (async/chan)]
                                          (world/trigger-disconnection! world ch)
                                          (assoc ch [world-key
                                                     (go
                                                       [world-key
                                                        ;; Current plan:
                                                        ;; pull the world
                                                        ;; state alteration
                                                        ;; function off
                                                        ;; the channel.
                                                        ;; This is neither
                                                        ;; feasible nor
                                                        ;; realistic.
                                                        ;; What really has
                                                        ;; to happen here
                                                        ;; is to signal each
                                                        ;; world (a
                                                        ;; WebWorker) to
                                                        ;; update
                                                        ;; its own state to
                                                        ;; do whatever it
                                                        ;; needs to cope with
                                                        ;; the disconnection.
                                                        ;; At this level,
                                                        ;; we're only
                                                        ;; concerned with the
                                                        ;; connection
                                                        ;; status.
                                                        ;; It would be good
                                                        ;; to do something
                                                        ;; like
                                                        ;; worlds.foreach
                                                        ;; 1 send disconnect
                                                        ;; 2 update state to
                                                        ;; disconnecting
                                                        ;; 3 update state to
                                                        ;; disconnected upon
                                                        ;; response
                                                        (async/<! ch)])])))
                                      {}
                                      worlds)]
          (when (> 0 (count disconnections))
            (let [timeout (async/timeout 1000)
                  [val ch] (async/alts! (conj (keys disconnections)
                                              timeout))]
              (if val
                (let [[[world-key world-updater]] val]
                  (swap! altered-worlds
                         (fn [world-map]
                           (update-in world-map
                                      [world-key ::world/internal-state]
                                      world-updater)))
                  (recur (dissoc disconnections ch)))
                (do
                  ;; FIXME: This should go to...where?
                  ;; Need a logger/state.
                  (println "Problem: World(s) update timed out")
                  ;; Need to update the remaining worlds to indicate
                  ;; that there was a state-changing problem
                  (throw (ex-info "Deal with this" (disconnections)))))))
          @altered-worlds)))
    (throw (js/Error "Need to disconnect each World")))
  (when sock
    (web-socket/close sock)))
