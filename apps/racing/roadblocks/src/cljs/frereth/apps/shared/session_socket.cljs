(ns frereth.apps.shared.session-socket
  "Wires all the session/socket/worker pieces together"
  (:require
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.window-manager :as window-manager]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.socket :as web-socket]
   [frereth.apps.shared.worker :as worker]
   [frereth.apps.shared.world :as world]
   [integrant.core :as ig]))

(enable-console-print!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::connection (s/keys :opt [::web-socket/wrapper]
                            :req [::lamport/clock
                                  ::session/manager
                                  ::web-socket/options  ; Q: Is this worth keeping after creation?
                                  ;; Q: Why is this referenced in here?
                                  ::window-manager/base
                                  ;; This is a bit redundant.
                                  ;; The ::worker/manager consists
                                  ;; of those 3 keys plus
                                  ;; ::worker/workers.
                                  ;; Then again, we should probably
                                  ;; treat it as a black box here.
                                  ;; The duplication is really just
                                  ;; an implementation detail.
                                  ::worker/manager]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Implementation

;;; Most of these should probably move into socket.
;;; Since this ns is about coordinating those.

(s/fdef recv-message!
  ;; Q: What *is* event?
  :args (s/cat :this ::connection
               :event any?))

(defn recv-message!
  ;; This function is nuts.
  ;; TODO: Refactor it into smaller pieces.
  ;; It seems like many/most of those really belong in the socket ns
  [{:keys [::lamport/clock
           ::session/manager]
    :as this} event]
  (.log js/console (str "Incoming at "
                        (.now js/Date)
                        " clock tick "
                        @clock
                        ": "
                        (js->clj event)))
  (let [raw-bytes (.-data event)
        raw-envelope (serial/array-buffer->string raw-bytes)
        envelope (serial/deserialize raw-envelope)
        {:keys [:frereth/action
                :frereth/body  ; Not all messages have a meaningful body
                :frereth/world-key]
         remote-lamport :frereth/lamport
         :or {remote-lamport 0}} envelope
        {:keys [::session/world-atom]} manager
        worlds @world-atom]
    (.log js/console "Read:" envelope)
    (lamport/do-tick clock remote-lamport)

    ;; Using condp for this is weak. Should probably use a defmethod,
    ;; at least. Or possibly even something like bidi.
    ;; What I remember about core.match seems like overkill, but it
    ;; also seems tailor-made for this. Assuming it is available in
    ;; cljs.
    ;; (It is, but it still seems like overkill for these purposes)
    ;; On the other hand...this approach should be fast.
    ;; Q: Worth switching to a Pedestal router/event chain?
    (condp = action
      :frereth/ack-forked
      (if-let [world (world/get-world-in-state worlds
                                               world-key
                                               ::world/forked)]
        (let [{:keys [:frereth/browser->worker
                      :frereth/web-worker]} world]
          (if web-worker
            ;; Q: Is this something I can consolidate with the
            ;; server-side version? (It doesn't seem all that likely)
            (let [browser->worker (fn [raw-message]
                                    (let [serialized (serial/serialize raw-message)
                                          wrapped {:frereth/action :frereth/forward
                                                   :frereth/body serialized}]
                                      (.postMessage web-worker wrapped))
                                    (throw (js/Error. "write this")))]
              (swap! world-atom
                     (fn [worlds]
                       (try
                         (world/activate-forked worlds world-key browser->worker)
                         (catch :default ex
                           (.error js/console "Forked ACK failed to adjust world state:" ex
                                   "TODO: Transition the offender to an error state")
                           ;; FIXME: This is wrong.
                           ;; Need to transition the offending world into an error state
                           worlds)))))

            (.error js/console "Missing forked worker"
                    {::problem envelope
                     ::world world
                     ;; Q: Why is this an empty dict?
                     ;; I can see the log-viewer world in the
                     ;; ::world/forked ::world/connection-state
                     ;; This gets stranger because I just established
                     ;; that I have a :world that matches.
                     ::world/forked (world/get-by-state worlds ::world/forked)
                     ::world-id world-key
                     :frereth/worlds worlds})))
        (.error js/console "::forked ACKed for missing world"
                {::problem envelope
                 ::world/forked (world/get-by-state worlds ::world/forked)
                 ::world-id world-key
                 :frereth/worlds worlds}))

      :frereth/ack-forking
      (try
        (.log js/console "Calling get-world-in-state")
        (if-let [world (world/get-world-in-state worlds
                                                 world-key
                                                 ::world/pending)]
          (if-let [ack-chan (::world/notification-channel world)]
            (let [success (async/put! ack-chan body)]
              (.log js/console "forking-ack Message put onto "
                    (js->clj ack-chan)
                    ": " success))
            (.error js/console "Missing ACK channel among\n"
                    (keys world)
                    "\nin\n"
                    world))
          (.error js/console "ACK about non-pending world"
                  {::count (count worlds)
                   ::world/world-map worlds
                   ::problem envelope
                   ::world/pending (world/get-by-state worlds ::world/pending)
                   ::world-id world-key}))
        (catch :default ex
          (.error js/console "Failed to handle :frereth/ack-forking" ex body)))

      :frereth/disconnect
      (if-let [world (world/get-world worlds world-key)]
        (if-let [worker (:frereth/web-worker world)]
          (.postMessage worker raw-envelope)
          (.error js/console "Disconnect message for"
                  world-key
                  "in"
                  envelope
                  ".\nNo matching worker in\n"
                  world))
        (.error js/console "Disconnect message for"
                world-key
                "in"
                envelope
                ".\nNo matching world in\n"
                worlds))

      :frereth/forward
      (if-let [world (world/get-world-in-state worlds world-key ::world/active)]
        (if-let [worker (:frereth/web-worker world)]
          (.postMessage worker (serial/serialize body))
          (.error js/console "Message for\n"
                  world-key
                  "in"
                  envelope
                  "\nMissing worker\n"
                  (keys world)
                  "\namong\n" world))
        ;; This should be impossible: it will throw an exception
        (.error js/console "Missing world" world-key "inside" worlds)))))

(s/fdef notify-logged-in!
  :args (s/cat :this ::connection
               :session-id ::session-id)
  :ret any?)
(defn notify-logged-in!
  ;; TODO: Auth before this. Some kind of PAKE is tempting.
  "Tell the web socket handler that a session just connected"
  [{worker-manager ::worker/manager
    :as this}
   session-id]
  ;; This is loosely based on the CurveCP handshake.

  ;; Remember the login protocol that never actually exchanges
  ;; passwords. (SRP: Secure Remote Password protocol)
  ;; c.f. https://www.owasp.org/index.php/Authentication_Cheat_Sheet

  ;; Honestly, the server could/should inject a "long-term" key-pair
  ;; at the top of main.js before serving it.
  ;; (Better: just use auth headers the way HTTPS intended)
  ;; For now, it doesn't much matter.
  ;; However:
  ;; There really needs to be an intermediate login step that
  ;; handles this for real.
  ;; In the frame of a linux ssh connection, loading the
  ;; web page was similar to connecting to a pty.
  ;; It's constantly tempting to allow anonymous connections
  ;; there.
  ;; That frame is wrong: this is like logging into
  ;; your local computer (and, no matter what Macs might
  ;; allow, doing that without authentication is wrong).
  ;; This is like the login "process" that authenticates the user.
  ;; Once that's done, there will be something like a JWT cookie
  ;; that we can check during the websocket connection.
  ;; We should be able to associate that JWT with the websocket
  ;; handler to let it know who has connected.
  ;; That JWT is probably what I was planning to use as the
  ;; session-id here.
  ;; Which makes this approach even more incorrect: that should
  ;; really be an http-only cookie to which we don't have any
  ;; access.
  (.log js/console "Trying to send logged-in notification for" this)
  (web-socket/send-message! worker-manager
                            ::login
                            {:path-info "/api/v1/logged-in"
                             :request-method :put
                             :params {:frereth/session-id session-id}}))

(defn check
  "This is really just for REPL verification"
  []
  (js/alert "defined"))

(defn configure-authenticated-system
  "Define the 2nd 'half' of System creation.

  The end-user has logged in. Let's set up 2nd phase of System to start
  doing interesting stuff."
  [{:keys [::lamport/clock
           ::window-manager/ctor
           ::window-manager/dtor!]
    :as options}]
  {:pre [clock
         ctor]}
  ;; This is where the window-manager/shell actually comes into
  ;; play.
  ;; Think of the dichotomy in recent Ubuntu versions between
  ;; VT 1 and 2 vs. VT 7.
  {::lamport/clock clock
   ::web-socket/wrapper (into {::lamport/clock (ig/ref ::lamport/clock)}
                              options)
   ::window-manager/base {::lamport/clock (ig/ref ::lamport/clock)
                          ::worker/manager (ig/ref ::worker/manager)
                          ::window-manager/ctor ctor
                          ::window-manager/dtor! dtor!}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef log-in!
  ;; Called to trigger the side-effects that start the "real" System
  :args (s/cat :options ::connection)
  :ret any?)
(defn log-in!
  [{:keys [::lamport/clock
           ::web-socket/wrapper]
    session-manager ::session/manager
    worker-manager ::worker/manager
    :as options}]
  "This is the 'real' entrypoint where all the interesting stuff can begin"
  (.log js/console "log-in!:" ::connection
        "session-manager:" session-manager
        "\namong:" (keys options)
        "\nin:" options)
  (let [configuration (configure-authenticated-system options)]
    (throw (js/Error. "Need to ig/start that configuration"))
    ;; Don't forget to merge it into an atom inside `this`
    ;; And clean it up during halt-keys!
    (if-let [{:keys [::web-socket/socket]} wrapper]
      (let [{:keys [:frereth/session-id]} session-manager]
        ;; Q: Worth using a library like sente or haslett to wrap the
        ;; details?
        (set! (.-onopen socket)
              (fn [event]
                (.log js/console "Websocket opened:" event socket)
                ;; This is really just a
                ;; placeholder until I add real auth.
                ;; And it falls apart in terms of anonymous sessions.
                ;; Anonymous sessions do not get access to a websock.
                ;; We got logged in before we ever got here.
                (notify-logged-in! options session-id)
                ;; TODO: This needs to wait for something like a
                ;; ::login-complete-ack message.
                ;; So we should set the .-onmessage to something
                ;; that handles that (and triggers this) first,
                ;; then reset it to the partial defined in the
                ;; line below.
                (worker/fork-shell! worker-manager session-id)))
        (set! (.-onmessage socket) (partial recv-message! options))
        (set! (.-onclose socket)
              (fn [event]
                ;; Right now, this updates the manager's world-map atom.
                ;; But we should really be tracking the manager's internal
                ;; state also.
                (.warn js/console "This should really update the manager value")
                (session/do-disconnect-all session-manager)))
        (set! (.-onerror socket)
              (fn [event]
                (.error js/console "Frereth Connection error:" event)))
        options)
      (.error js/console "Missing web-socket"))))

(defmethod ig/init-key ::connection
  [_ this]
  ;; This seems pointless.
  ;; Q: What happens if I just don't define it?
  this)
