(ns backend.system
  (:require [backend.web.server]
            [client.propagate :as propagate]
            [clojure.core.async :as async]
            [server.networking]
            [frereth.weald
             [logging :as log]
             [specs :as weald]]
            [integrant.core :as ig]))

(defmethod ig/init-key ::log-chan
  [_ _]
  ;; FIXME: Allow options to override
  {::ch (async/chan)})

(defmethod ig/halt-key! ::log-chan
  [_ {:keys [::ch]
      :as config}]
  (async/close! ch)
  (assoc config ::ch nil))

(defmethod ig/init-key ::logger
  [_ {:keys [::log-chan]
      :as opts}]
  ;; The only real reason to use a CompositeLog here is
  ;; to test it out.
  ;; Then again, debugging what the browser displays isn't a terrible
  ;; idea.
  ;; Writing to a file would probably be better for debugging than
  ;; STDOUT.
  (assoc opts ::weald/logger
         (log/composite-log-factory [(log/std-out-log-factory)
                                     (log/async-log-factory (::ch log-chan))])))
;; It's tempting to add a corresponding halt! handler for ::logger,
;; but we don't actually have the log-state here.
;; TODO: Change that.
;; And decide which is the chicken vs. the egg.

(defn ctor [opts]
  ;; Changes in here don't really show up through a simple
  ;; call to reset.
  ;; FIXME: That isn't acceptable.
  ;; It has something to do with the way the boot task was defined.
  {:backend.web.server/web-server {::web-server opts}
   ::log-chan (::log-chan opts)
   ::logger (into {::chan (ig/ref ::log-chan)}
                  (::logger opts))
   ::propagate/monitor (into {::propagate/log-chan (ig/ref ::log-chan)}
                             (::monitor opts))
   ;; FIXME: This pretty much needs to call frereth-cp.server/ctor
   ;; to set up the method to call server.networking/start! to trigger
   ;; the side-effects.
   :server.networking/handler (into {:server.networking/port 32154
                                     ::weald/logger (ig/ref ::logger)}
                                    (::server opts))})
