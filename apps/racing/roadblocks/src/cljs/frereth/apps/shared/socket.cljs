(ns frereth.apps.shared.socket
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [frereth.apps.shared.serialization :as serial]
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
  [_ {:keys [::ws-url ::lamport/clock]
      :as opts}]
  (lamport/do-tick clock)
  (.log js/console "Connecting WebSocket to"
        ws-url
        "for world interaction based on"
        (clj->js opts))
  (try
    (let [ws (js/WebSocket. ws-url)]
      ;; A blob is really just a file handle. Have to jump through an
      ;; async op to convert it to an arraybuffer.
      (set! (.-binaryType ws) "arraybuffer")
      (lamport/do-tick clock)
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

(s/fdef send-message!
  :args (s/cat :this ::manager
               :world-id :frereth/world-key
               ;; This really turns into a subset of the Pedestal
               ;; Request map.
               ;; TODO: Need to define exactly which subset.
               ;; And think this through a bit more.
               ;; Would it be worth splitting into something
               ;; more traditional, like post/get/delete
               ;; methods?
               :request any?))
(defn send-message!
  ;; FIXME: Rename this to something like send-to-server!
  ;; FIXME: This does not belong in here. I need to mock
  ;; it out for the sake of an in-browser mock websocket
  "Send `body` over `socket` for `world-id`"
  [{{:keys [::socket]} ::wrapper
    :keys [::lamport/clock]
    :as this}
   world-id
   request]
  {:pre [socket]}
  (when-not clock
    (throw (ex-info "Missing clock"
                    {::problem this})))
  (lamport/do-tick clock)
  (let [envelope {:request request
                  :frereth/lamport @clock
                  :frereth/wall-clock (.now js/Date)
                  :frereth/world-key world-id}]
    ;; TODO: Check that bufferedAmount is low enough
    ;; to send more
    (try
      (.log js/console "Trying to send-message!" envelope)
      (.send socket (serial/serialize envelope))
      (.log js/console request "sent successfully")
      (catch :default ex
        (.error js/console "Sending message failed:" ex)))))
