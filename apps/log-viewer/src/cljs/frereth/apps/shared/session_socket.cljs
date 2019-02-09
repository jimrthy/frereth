(ns frereth.apps.shared.session-socket
  "Wires everything together"
  (:require
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [cognitect.transit :as transit]
   [frereth.apps.log-viewer.frontend.session :as session]
   [frereth.apps.log-viewer.frontend.socket :as web-socket]
   [integrant.core :as ig]
   [shared.lamport :as lamport]
   [shared.world :as world]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::connection (s/keys :required [::lamport/clock
                                       ::session/manager
                                       ::web-socket/sock]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Implementation

;;; These duplicate pieces in core

(defn array-buffer->string
  "Convert a js/ArrayBuffer to a string"
  [bs]
  (let [data-view (js/DataView. bs)
        ;; Q: What encoding is appropriate here?
        ;; (apparently javascript strings are utf-16)
        decoder (js/TextDecoder. "utf-8")]
    (.decode decoder data-view)))

(s/fdef recv-message!
  ;; Q: What *is* event?
  :args (s/cat :this ::connection
               :event any?))

(let [;; Q: msgpack ?
      ;; A: Not in clojurescript, yet.
      ;; At least, not "natively"
      reader (transit/reader :json)
      writer (transit/writer :json)]
  (defn serialize
    "Encode using transit"
    [o]
    (transit/write writer o))

  (defn recv-message!
    [{:keys [::lamport/clock
             ::session/manager]
      :as this} event]
    (console.log (str "Incoming at "
                      (.now js/Date)
                      " clock tick "
                      @clock
                      ": "
                      (js->clj event)))
    (let [raw-envelope (array-buffer->string (.-data event))
          _ (console.log "Trying to read" raw-envelope)
          envelope (transit/read reader raw-envelope)
          {:keys [:frereth/action
                  :frereth/body  ; Not all messages have a meaningful body
                  :frereth/world-key]
           remote-lamport :frereth/lamport
           :or [remote-lamport 0]} envelope]
      (console.log "Read:" envelope)
      (swap! clock
             (fn [current]
               (if (>= remote-lamport current)
                 (inc remote-lamport)
                 (inc current))))
      (let [{:keys [::session/world-atom]} manager
            worlds @world-atom]
        ;; Using condp for this is weak. Should probably use a defmethod,
        ;; at least. Or possibly even something like bidi.
        ;; What I remember about core.match seems like overkill, but it
        ;; also seems tailor-made for this. Assuming it is available in
        ;; cljs.
        ;; (It is, but it still seems like overkill for these purposes)
        (condp = action
          :frereth/ack-forked
          (if-let [{:keys [::worker]} (world/get-world-in-state worlds
                                                                world-key
                                                                ::world/forked)]
            (swap! world-atom
                   (fn [worlds]
                     (try
                       (throw (js/Error "Need the client connection parameter"))
                       (world/activate-pending worlds world-key nil)
                       (catch :default ex
                         (console.error "Forked ACK failed to adjust world state:" ex
                                        "TODO: Transition the offender to an error state")
                         ;; FIXME: This is wrong.
                         ;; Need to transition the offending world into an error state
                         worlds))))

            (console.error "Missing forked worker"
                           {::problem envelope
                            ::world/forked (world/get-by-state worlds ::world/forked)
                            ::world-id world-key}))

          :frereth/ack-forking
          (try
            (if-let [ack-chan (::waiting-ack (world/get-world-in-state worlds
                                                                       world-key
                                                                       ::world/pending))]
              (let [success (async/put! ack-chan body)]
                (console.log (str "Message put onto " ack-chan
                                  ": " success)))
              (console.error "ACK about non-pending world"
                             {::problem envelope
                              ::world/pending (world/get-by-state worlds ::world/pending)
                              ::world-id world-key}))
            (catch :default ex
              (console.error "Failed to handle :frereth/ack-forking" ex body)))

          :frereth/disconnect
          (if-let [worker (::worker (world/get-world worlds world-key))]
            (.postMessage worker raw-envelope)
            (console.error "Disconnect message for"
                           world-key
                           "in"
                           envelope
                           ". No match in"
                           @worlds))

          :frereth/forward
          (if-let [world (world/get-world-in-state worlds world-key ::world/active)]
            (if-let [worker (::worker world)]
              (.postMessage worker (serialize body))
              (console.error "Message for"
                             world-key
                             "in"
                             envelope
                             ". Missing worker"
                             (keys world)
                             "among" world))
            ;; This is impossible: it will throw an exception
            (console.error "Missing world" world-key "inside" worlds)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::connection
  [_ {:keys [::lamport/clock
             ::session/manager
             ::web-socket/sock]}]
  (when-let [{:keys [::web-socket/socket]} sock]
    (let [{:keys [::session/session-id]} manager]
      ;; Q: Worth using a library like sente or haslett to wrap the
      ;; details?
      ;; TODO: Move these into session-socket
      (set! (.-onopen sock)
            (fn [event]
              (console.log "Websocket opened:" event sock)
              (fork-login! sock session-id)
              ;; This is where things like deferreds, core.async,
              ;; and promises come in handy.
              ;; Once the login sequence has completed, we want to spin
              ;; up the top-level shell (which, in this case, is our
              ;; log-viewer Worker)
              (shell-forker sock session-id)))
      (set! (.-onmessage sock) (partial recv-message! clock))
      (set! (.-onclose sock)
            (fn [event]
              ;; Right now, this updates the manager's world-map atom.
              ;; But we should really be tracking the manager's internal
              ;; state also.
              (console.warn "This should really update the manager value")
              (session/do-disconnect-all manager)))
      (set! (.-onerror sock)
            (fn [event]
              (console.error "Frereth Connection error:" event))))))
