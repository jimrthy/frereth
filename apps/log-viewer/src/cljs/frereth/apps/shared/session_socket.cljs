(ns frereth.apps.shared.session-socket
  "Wires everything together"
  (:require
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.log-viewer.frontend.session :as session]
   [frereth.apps.log-viewer.frontend.socket :as web-socket]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.worker :as worker]
   [integrant.core :as ig]
   [shared.lamport :as lamport]
   [shared.world :as world]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::connection (s/keys :required [::lamport/clock
                                       ::session/manager
                                       ::web-socket/wrapper
                                       ::worker/manager]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Implementation

;;; These duplicate pieces in core
;;; And most of them should probably move into socket.
;;; Since this ns is about coordinating those.

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

(s/fdef send-message!
  :args (s/cat :socket any?
               ;; should be bytes? but cljs doesn't have the concept
               ;; Q: Is it a string?
               ;; A: Actually, it should be a map of a JWK
               :world-id any?
               ;; Anything that transit can serialize natively, anyway
               :body any?))

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
  (let [raw-bytes (.-data event)
        raw-envelope (array-buffer->string raw-bytes)
        envelope (serial/deserialize raw-envelope)
        {:keys [:frereth/action
                :frereth/body  ; Not all messages have a meaningful body
                :frereth/world-key]
         remote-lamport :frereth/lamport
         :or [remote-lamport 0]} envelope]
    (console.log "Read:" envelope)
    (lamport/do-tick clock remote-lamport)

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
            (.postMessage worker (serial/serialize body))
            (console.error "Message for"
                           world-key
                           "in"
                           envelope
                           ". Missing worker"
                           (keys world)
                           "among" world))
          ;; This is impossible: it will throw an exception
          (console.error "Missing world" world-key "inside" worlds))))))

(defn send-message!
  "Send `body` over `socket` for `world-id`"
  [{:keys [::lamport/clock
           ::web-socket/sock]
    :as this}
   world-id
   body]
  (lamport/do-tick clock)
  (let [envelope {:frereth/body body
                  :frereth/lamport @clock
                  :frereth/wall-clock (.now js/Date)
                  :frereth/world world-id}]
    ;; TODO: Check that bufferedAmount is low enough
    ;; to send more
    (try
      (println "Trying to send-message!" envelope)
      (.send sock (serial/serialize envelope))
      (println body "sent successfully")
      (catch :default ex
        (console.error "Sending message failed:" ex)))))

;;; aka fork-login! in core
(s/fdef notify-logged-in!
  :args (s/cat :this ::connection
               :session-id ::session-id)
  :ret any?)
(defn notify-logged-in!
  ;; TODO: Auth before this. Probably using some kind of PAKE.
  "Let the web socket handler which session just connected"
  [this session-id]
  ;; Probably reasonable to base this on something like the
  ;; CurveCP handshake.

  ;; Remember the login protocol that never actually exchanges
  ;; passwords. (SRP: Secure Remote Password protocol)
  ;; c.f. https://www.owasp.org/index.php/Authentication_Cheat_Sheet

  ;; Honestly, the server could/should inject a "long-term" key-pair
  ;; at the top of this file before serving it.
  ;; For now, it doesn't much matter.
  ;; However:
  ;; There really needs to be an intermediate login step that
  ;; handles this for real.
  ;; In terms of a linux ssh connection, opening the
  ;; websocket is similar to connecting to a pty.
  ;; We need a login "process" that authenticates the user,
  ;; using this (I'm starting to think of it as a "session
  ;; key) to help the server side correlate between the
  ;; original http request and the websocket connection
  ;; that, really, gets authorized during login.
  (send-message! this ::login session-id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::connection
  [_ {:keys [::lamport/clock
             ::session/manager
             ::web-socket/wrapper]
      :as this}]
  (when-let [{:keys [::web-socket/socket]} wrapper]
    (let [{:keys [::session/session-id]} manager]
      ;; Q: Worth using a library like sente or haslett to wrap the
      ;; details?
      ;; TODO: Move these into session-socket
      (set! (.-onopen socket)
            (fn [event]
              (console.log "Websocket opened:" event socket)
              (notify-logged-in! this session-id)
              ;; This is where things like deferreds, core.async,
              ;; and promises come in handy.
              ;; Once the login sequence has completed, we want to spin
              ;; up the top-level shell (which, in this case, is our
              ;; log-viewer Worker)
              ;; Q: shouldn't this come from the session/manager?
              ;; After all, it should know what the user's shell
              ;; *is*.
              ;; A: Well, sort-of.
              ;; There are a few more steps before we get to that.
              (worker/fork-shell! this session-id)))
      (set! (.-onmessage socket) (partial recv-message! clock))
      (set! (.-onclose socket)
            (fn [event]
              ;; Right now, this updates the manager's world-map atom.
              ;; But we should really be tracking the manager's internal
              ;; state also.
              (console.warn "This should really update the manager value")
              (session/do-disconnect-all manager)))
      (set! (.-onerror socket)
            (fn [event]
              (console.error "Frereth Connection error:" event)))
      (assoc this ::worker/manager (worker/manager clock manager wrapper)))))
