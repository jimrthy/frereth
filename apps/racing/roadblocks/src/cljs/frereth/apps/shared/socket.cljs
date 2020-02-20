(ns frereth.apps.shared.socket
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [frereth.apps.shared.lamport :as lamport]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::socket #(instance? js/WebSocket %))

(s/def ::wrapper (s/keys :req [::socket]
                         :opt [::base-url
                               ::ws-url]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Implementation

(defmethod ig/init-key ::wrapper
  [_ {:keys [::ws-url ::shell-forker ::session-id ::lamport/clock]
      :as opts}]
  (.log js/console "Connecting WebSocket to"
        ws-url
        "for world interaction based on"
        (clj->js opts))
  (try
    (let [ws (js/WebSocket. ws-url)]
      ;; A blob is really just a file handle. Have to jump through an
      ;; async op to convert it to an arraybuffer.
      (set! (.-binaryType ws) "arraybuffer")
      (assoc opts ::socket ws))
    (catch :default ex
      (.error js/console "Initializing wrapper failed:" ex
              "\nbased on" opts)
      nil)))

(defmethod ig/halt-key! ::wrapper
  [_ {:keys [::socket] :as this}]
  (when socket
    (.close socket)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn close
  [{:keys [::socket]
    :as wrapper}]
  (.close socket))

(s/fdef wrapper
  :args nil
  :ret ::wrapper)
(defn wrapper
  "Returns a wrapper ready to abstract around a web-socket"
  []
  {::socket nil})
