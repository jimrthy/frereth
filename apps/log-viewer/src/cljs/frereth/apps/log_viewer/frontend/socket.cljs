(ns frereth.apps.log-viewer.frontend.socket
  ;; cemerick.url is very tempting, but it hasn't been updated
  ;; in years. There are 2 or 3 "Is this project still active?"
  ;; issues filed against it, and ~30 forks.
  ;; Plus...does it really add all that much?
  (:require [cemerick.url :as url]
            [clojure.spec.alpha :as s]
            [clojure.string]
            [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; This is an instance of a js/WebSocket
(s/def ::sock any?)

(s/def ::wrapper (s/keys :req [::sock]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Implementation

;;; These duplicate pieces in core

(def base-url
  "Also needed by web workers"
  ;; Very well *might* be worth putting into a Component
  (atom ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defmethod ig/init-key ::sock
  [_ {:keys [::shell-forker ::session-id]
      :as opts}]
  (console.log "Connecting WebSocket for world interaction")
  (try
    (let [location (.-location js/window)
          origin-protocol (.-protocol location)
          protocol-length (count origin-protocol)
          protocol (if (= \s
                          (clojure.string/lower-case (aget origin-protocol
                                                           (- protocol-length 2))))
                     "wss:"
                     "ws:")
          local-base-suffix (str "//" (.-host location))
          url (str protocol local-base-suffix  "/ws")
          ws (js/WebSocket. url)]
      (reset! base-url (url/url (str origin-protocol local-base-suffix)))
      ;; A blob is really just a file handle. Have to jump through an
      ;; async op to convert it to an arraybuffer.
      (set! (.-binaryType ws) "arraybuffer")
      ;; Q: Worth using a library like sente or haslett to wrap the
      ;; details?
      (set! (.-onopen ws)
            (fn [event]
              (console.log "Websocket opened:" event ws)
              (throw (js/Error "This is the point to this Component"))
              (swap! shared-socket
                     (fn [existing]
                       (when existing
                         ;; This part should probably just be handled by halt-key!
                         ;; Q: Right?
                         (.close existing))
                       ws))

              (fork-login! ws session-id)
              ;; This is where things like deferreds, core.async,
              ;; and promises come in handy.
              ;; Once the login sequence has completed, we want to spin
              ;; up the top-level shell (which, in this case, is our
              ;; log-viewer Worker)
              (shell-forker ws session-id)))
      (set! (.-onmessage ws) recv-message!)
      ;; FIXME: These next two need to update the system
      (set! (.-onclose ws)
            (fn [event]
              (console.warn "Frereth Connection closed:" event)))
      (set! (.-onerror ws)
            (fn [event]
              (console.error "Frereth Connection error:" event))))
    (catch :default ex
      (console.error ex))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

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
