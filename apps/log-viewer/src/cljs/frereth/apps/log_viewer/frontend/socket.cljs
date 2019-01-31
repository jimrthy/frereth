(ns frereth.apps.log-viewer.frontend.socket
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]))

;; This is an instance of a js/WebSocket
(s/def ::sock any?)

(s/def ::wrapper (s/keys :req [::sock]))

(defn close
  [{:keys [::sock]
    :as socket}]
  (.close sock))

(s/fdef wrapper
  :args nil
  :ret ::wrapper)
(defn wrapper
  "Returns a wrapper ready to abstract around a web-socket"
  []
  {::sock nil})
