(ns backend.system
  (:require [backend.web.server]
            [client.propagate :as propagate]
            [clojure.core.async :as async]
            [server.networking]
            [frereth.weald
             [logging :as log]
             [specs :as weald]]
            [integrant.core :as ig]
            [frereth-cp.message.specs :as msg-specs]))

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

(defn server-child-handler
  "Doesn't belong in here. But it's a start"
  [incoming]
  (println (str "Server child received:" (vec incoming)
                ", a " (class incoming))))

(defn ctor [opts]
  ;; Changes in here don't really show up through a simple
  ;; call to reset.
  ;; FIXME: That isn't acceptable.
  ;; It has something to do with the way the boot task was defined.
  {:backend.web.server/web-server {::web-server opts}
   ::log-chan (::log-chan opts)
   ::logger (into {::chan (ig/ref ::log-chan)}
                  (::logger opts))
   ;; Note that this is really propagating the Server logs.
   ;; The Client logs are really quite different...though it probably
   ;; makes sense to also send those here, at least for an initial
   ;; implementation.
   ::propagate/monitor (into {::propagate/log-chan (ig/ref ::log-chan)}
                             (::monitor opts))
   ;; FIXME: This pretty much needs to call frereth-cp.server/ctor
   ;; to set up the method to call server.networking/start! to trigger
   ;; the side-effects.
   ;; FIXME: This needs a UDP socket.
   ;; FIXME: Connecting a new World Renderer needs to create a new
   ;; UDP socket along with its own CurveCP Client that tries to
   ;; connect to this.
   :server.networking/server (into {::msg-specs/->child server-child-handler
                                    :server.networking/extension-vector (range 16)
                                    ::weald/logger (ig/ref ::logger)
                                    :server.networking/my-name "log-viewer.test.frereth.com"
                                    :server.networking/port 32154}
                                   (::server opts))})
