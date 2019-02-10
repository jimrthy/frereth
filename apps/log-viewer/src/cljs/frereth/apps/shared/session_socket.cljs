(ns frereth.apps.shared.session-socket
  "Wires everything together"
  (:require
   [cemerick.url :as url]
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

(s/fdef str->array
  :args (s/cat :s string?)
  :ret ::array-buffer)
(defn str->array
  "Convert a string to a js/ArrayBuffer"
  [s]
  ;; Translated from https://gist.github.com/andreburgaud/6f73fd2d690b629346b8
  ;; There's an inverse function there named arrayBufferToString which
  ;; is worth contrasting with array-buffer->string in terms of
  ;; performance.
  ;; Javascript:
  ;; String.fromCharCode.apply(null, new Uint16Array(buf));
  (let [buf (js/ArrayBuffer. (* 2 (count s)))
        buf-view (js/Uint16Array. buf)]
    (dorun (map-indexed (fn [idx ch]
                          (aset buf-view idx (.charCodeAt ch)))
                        s))
    buf))

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

(let [;; Q: msgpack ?
      ;; A: Not in clojurescript, yet.
      ;; At least, not "natively"
      writer (transit/writer :json)]

  (defn serialize
    "Encode using transit"
    [o]
    (transit/write writer o)))

(let [reader (transit/reader :json)]
  (defn deserialize
    [s]
    (console.log "Trying to read" s)
    (transit/read reader s)))

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
        envelope (deserialize raw-envelope)
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
            (.postMessage worker (serialize body))
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
      (.send sock (serialize envelope))
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

;; I'm skeptical that this belongs in here.
;; It's rapidly turning into a catch-all Swiss army knife.
;; Putting this sort of actual functionality into a worker-manager
;; ns seems like it would make more sense.
;; This this ns can add that to the list of other namespaces that
;; it coordinates.
(defn on-worker-message
  "Cope with message from Worker"
  [socket world-key worker event]
  (let [data (.-data event)]
    (try
      (let [{:keys [:frereth/action]
             :as raw-message} (transit/read (transit/reader :json)
                                            data)]
        (console.log "Message from" worker ":" action)
        (condp = action
          :frereth/render
          (let [dom (sanitize-scripts worker
                                      (:frereth/body raw-message))]
            (r/render-component [(constantly dom)]
                                (js/document.getElementById "app")))
          :frereth/forked
          (let [{:keys [::cookie]
                 :as world-state} (do-get-world world-key ::forking)
                decorated (assoc raw-message
                                 :frereth/cookie cookie
                                 :frereth/pid world-key)]
            (if cookie
              (do
                (send-message! socket
                               world-key
                               ;; send-message! will
                               ;; serialize this
                               ;; again.
                               ;; Which is wasteful.
                               ;; Would be better to
                               ;; just have this
                               ;; :action as a tag,
                               ;; followed by the
                               ;; body.
                               #_data
                               ;; But that's premature
                               ;; optimization.
                               ;; Worry about that
                               ;; detail later.
                               ;; Even though this is
                               ;; the boundary area
                               ;; where it's probably
                               ;; the most important.
                               ;; (Well, not here. But in general)
                               decorated)
                (adjust-world-state! world-key
                                     ::forking
                                     ::forked
                                     {::cookie cookie
                                      ::worker worker}))
              (console.error "Missing ::cookie among" world-state)))))
      (catch :default ex
        (console.error ex "Trying to deserialize" data)))))

(s/fdef spawn-worker!
  :args (s/cat :crypto ::subtle-crypto
               :this ::connection
               :session-id ::session-id
               :key-pair ::internal-key-pair
               ;; Actually, this is anything that transit can serialize
               :public ::jwk
               :cookie ::cookie)
  :ret (s/nilable ::web-worker))
(defn spawn-worker!
  "Begin promise chain that leads to an ::active World"
  [crypto
   {:keys [::session/manager
           ::web-socket/sock]
    :as this}
   session-id
   {:keys [::secret]}
   world-key
   cookie]
  (when window.Worker
    (let [base-url (::web-socket/base-url sock)]
      (console.log "Constructing Worker fork URL based on" base-url
                   "signing with secret-key" secret)
      ;; This is missing a layer of indirection.
      ;; The worker this spawns should return a shadow
      ;; DOM that combines all the visible Worlds (like
      ;; an X11 window manager). That worker, in turn,
      ;; should spawn other workers that communicate
      ;; with it to supply their shadow DOMs into its.
      ;; In a lot of ways, this approach is like setting
      ;; up X11 to run a single app rather than a window
      ;; manager.
      ;; That's fine as a first step for a demo, but don't
      ;; get delusions of grandeur about it.
      ;; Actually, there's another missing layer here:
      ;; Each World should really be loaded into its
      ;; own isolated self-hosted compiler environment.
      ;; Then available workers should be able to pass them
      ;; (along with details like window location) around
      ;; as they have free CPU cycles.
      ;; Q: Do web workers provide that degree of isolation?
      ;; Q: Web Workers are designed to be relatively heavy-
      ;; weight. Is it worth spawning a new one for each
      ;; world (which are also supposed to be pretty hefty).
      (let [path-to-shell (::session/path-to-shell manager)
            url (url/url base-url path-to-shell)
            initiate {:frereth/cookie cookie
                      :frereth/session-id session-id
                      :frereth/world-key world-key}
            packet-string (serialize initiate)
            packet-bytes (str->array packet-string)
            _ (console.log "Signing" packet-bytes)
            signature-promise (.sign crypto
                                     #js {:name "ECDSA"
                                          :hash #js{:name "SHA-256"}}
                                     secret
                                     packet-bytes)]
        ;; TODO: Look at the way promesa handles this
        ;; Q: Is it really any cleaner?
        (.then signature-promise
               (fn [signature]
                 (let [params {:frereth/initiate packet-string
                               :frereth/signature (-> signature
                                                      array-buffer->string
                                                      serialize)}
                       ;; Web server uses the cookie to verify that
                       ;; the request comes from the correct World.
                       worker (new window.Worker
                                   (str (assoc url :query params))
                                   ;; Currently redundant:
                                   ;; in Chrome, at least, module scripts are not
                                   ;; supported on DedicatedWorker
                                   #js{"type" "classic"})]
                   (set! (.-onmessage worker)
                         (partial on-worker-message sock world-key worker))
                   (set! (.-onerror worker)
                         (fn [problem]
                           (console.error "FIXME: Need to handle"
                                          problem
                                          "from World"
                                          world-key)))
                   worker)))))))

(s/fdef export-public-key-and-build-worker!

  :args (s/cat :socket ::web-socket
               :session-id ::session-id
               ;; FIXME: Spec this
               :crypto any?
               :key-pair ::key-pair)
  ;; It's tempting to spec that this returns a future.
  ;; But that's just an implementation detail. This is
  ;; called for side-effects.
  ;; Then again, having it return a promise (or whatever
  ;; promesa does) would have probably been cleaner than
  ;; dragging core.async into the mix.
  :ret any?)
(defn export-public-key-and-build-worker!
  [{:keys [::web-socket/sock]
    :as this}
   session-id crypto key-pair]
  (let [raw-secret (.-privateKey key-pair)
        raw-public (.-publicKey key-pair)
        exported (.exportKey crypto "jwk" raw-public)]
    (.then exported
           (fn [public]
             (build-worker-from-exported-key
              sock
              (partial spawn-worker!
                       crypto
                       this
                       session-id
                       {::public raw-public
                        ::secret raw-secret}
                       (js->clj public))
              key-pair
              public)))))

(defn fork-shell!
  [{:keys [::web-socket/sock]
    :as this}
   session-id]
  ;; TODO: Look into using something like
  ;; https://tweetnacl.js.org/#/
  ;; instead.
  ;; It seems like it might be faster, and definitely simpler,
  ;; but the ability to use a native API that the browser
  ;; writer optimized probably offsets those advantages.
  (let [crypto (.-subtle (.-crypto js/window))
        ;; Q: Any point to encrypting?
        ;; Q: Are these settings reasonable?
        ;; As a first step, I really just want a randomly-generated
        ;; public key that I can use as a PID to make it difficult for
        ;; other "processes" to interfere.
        signing-key-promise (.generateKey crypto
                                          #js {:name "ECDSA"
                                               :namedCurve "P-384"}
                                          true
                                          #js ["sign"
                                               "verify"])]
    (.then signing-key-promise (partial export-public-key-and-build-worker!
                                        this
                                        session-id
                                        crypto))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::connection
  [_ {:keys [::lamport/clock
             ::session/manager
             ::web-socket/sock]
      :as this}]
  (when-let [{:keys [::web-socket/socket]} sock]
    (let [{:keys [::session/session-id]} manager]
      ;; Q: Worth using a library like sente or haslett to wrap the
      ;; details?
      ;; TODO: Move these into session-socket
      (set! (.-onopen sock)
            (fn [event]
              (console.log "Websocket opened:" event sock)
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
              (fork-shell! this session-id)))
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
