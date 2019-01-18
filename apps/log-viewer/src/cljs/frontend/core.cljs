(ns frontend.core
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [frontend.macro :refer [foobar]])
  ;; cemerick.url is very tempting, but it hasn't been updated
  ;; in years. There are 2 or 3 "Is this project still active?"
  ;; issues filed against it, and ~30 forks.
  ;; Plus...does it really add all that much?
  (:require [cemerick.url :as url]
            [cljs.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string]
            [cognitect.transit :as transit]
            [foo.bar]
            ;; Start by at least partially supporting this, since it's
            ;; so popular
            [reagent.core :as r]
            [weasel.repl :as repl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: Define these
(s/def ::async-chan any?)
;; Black box the web server sent as part of its forking-ack response.
;; Right now, this is an Uint8Array.
;; I'm dubious about this decision.
(s/def ::cookie any?)

;; Instance of ArrayBuffer
(s/def ::array-buffer any?)
(s/def ::jwk map?)
;; Something like crypto/Key
(s/def ::crypto-key any?)
(s/def ::public-key ::crypto-key)
(s/def ::public ::public-key)
(s/def ::secret ::crypto-key)
(s/def ::internal-key-pair (s/keys :required [::public ::secret]))
;; Something like crypto/KeyPair
;; i.e. the native javascript equivalent of ::internal-key-pair
(s/def ::key-pair any?)

(s/def ::session-id any?)
;; Instance of SubtleCrypto
(s/def ::subtle-crypto any?)
(s/def ::web-socket any?)
(s/def ::web-worker any?)

;; This is some sort of human-readable/transit-serializable
;; representation of a World's public key
;; TODO: Take advantage of renderer.world
(s/def ::world-key map?)
(s/def ::world-state-keys #{::active ::forked ::forking ::pending})
(s/def ::state ::world-state-keys)
;; FIXME: The legal values here are pretty interesting.
;; And, honestly, specific to each state-key
(s/def ::world-state (s/or :pending (s/keys :req [::waiting-ack
                                                  ::state])))

(s/def work-spawner (s/fspec :args (s/cat :cookie ::cookie)
                             :ret ::web-worker))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)

(def base-url (atom ""))

(def idle-worker-pool
  "Workers were designed to be heavyweight. How dangerous to pool?"
  (atom []))
(comment
  (println idle-worker-pool)
  (println (cljs->js @idle-worker-pool))
  (console.log (-> idle-worker-pool deref first))
  (.postMessage (-> idle-worker-pool deref first) ::abc)
  )

(def session-id-from-server
  "Need something we can use for authentication and validation

  There are multiple levels to this. Each World instance needs its own
  key [pair?]"
  [-39 -55 106 103
   -31 117 120 57
   -102 12 -102 -36
   32 77 -66 -74
   97 29 9 16
   12 -79 -102 -96
   89 87 -73 116
   66 43 39 -61])

;;; Q: Put these globals into a single atom?

(def shared-socket (atom nil))

;; Maps of world-keys to state
;; TODO: Move this somewhere less accessible. Then pass that through
;; to all event handlers which need access.
;; Note that, in the case of messages from a World, the handler should
;; really only have access to that World.
;; Although there will probably be exceptions, like debuggers in
;; developer mode.
(def worlds
  "pending worlds have notified the web server that they want to fork.
  forking worlds have received the keys to do so and are in the process
  of setting up the Web Worker.
  forked worlds have sent the notification from the Web Worker and are
  ready to begin interacting.
  active worlds have received a message from the web server."
  ;; It's very interesting to write/find a real FSM manager
  ;; to cope with these transitions.
  ;; TODO: Merge these. Just track the state with each World.
  (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef adjust-world-state!
  :args (s/or :modify-state (s/cat :world-key ::world-key
                                   :from ::world-state-keys
                                   :to ::world-state-keys
                                   :additional (s/or :to-merge map?
                                                     :to-update (s/fspec
                                                                 :args (s/cat :current map?)
                                                                 :ret map?)))
              :retain-state (s/cat :world-key ::world-key
                                   :from ::world-state-keys
                                   :to ::world-state-keys)))
(defn adjust-world-state!
  "This is really an FSM update"
  ([world-key from to additional]
   (if-let [current (get @worlds world-key)]
     (do
       (console.log "Planning to switch" current
                    "state from" from
                    "to" to
                    "and adjust state by" additional)
       (if (= (::state current) from)
         (let [added (condp (fn [test-expr expr]
                              (test-expr expr))
                         additional
                       map? (into current additional)
                       fn? (additional current)
                       nil? current)
               updated (assoc added ::state to)]
           (swap! worlds
                  #(assoc % world-key updated)))
         (throw (ex-info "Mismatched state"
                         {::expected from
                          ::actual (::state current)
                          ::world current}))))
     (throw (ex-info "Missing current world"
                     {::actual @worlds
                      ::expected-state from
                      ::world-key world-key}))))
  ([world-key from to]
   (adjust-world-state! world-key from to nil)))

(s/fdef do-get-world
  :args (s/or :stateful (s/cat :world-key ::world-key
                               :state ::world-state-keys)
              :stateless (s/cat :world-key ::world-key))
  :ret (s/nilable ::world-state))
(defn do-get-world
  ([world-key]
   (-> worlds deref (get world-key)))
  ([world-key state]
   (if-let [world (do-get-world world-key)]
     (let [world-state (::state world)]
       (if (= world-state state)
         world
         (throw (ex-info "Request for world in the wrong state"
                         {::requested state
                          ::actual world-state
                          ::world world}))))
     (throw (ex-info "Missing requested world"
                     {::world-key world-key
                      ::worlds @worlds})))))

(s/fdef get-worlds-by-state
  :args (s/cat :state ::world-state-keys)
  :ret (s/coll-of ::world-state))
(defn get-worlds-by-state
  [state]
  (filter #(= (::state %) state) @worlds))

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
  :args (s/cat :event any?))

(s/fdef send-message!
  :args (s/cat :socket any?
               ;; should be bytes? but cljs doesn't have the concept
               ;; Q: Is it a string?
               ;; A: Actually, it should be a map of a JWK
               :world-id any?
               ;; Anything that transit can serialize natively, anyway
               :body any?))

(let [lamport (atom 0)
      ;; Q: msgpack ?
      ;; A: Not in clojurescript, yet.
      ;; At least, not "natively"
      reader (transit/reader :json)
      writer (transit/writer :json)]

  (defn serialize
    "Encode using transit"
    [o]
    (transit/write writer o))

  (defn recv-message!
    [event]
    (console.log (str "Incoming at "
                      (.now js/Date)
                      " clock tick "
                      @lamport
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
      (swap! lamport
             (fn [current]
               (if (>= remote-lamport current)
                 (inc remote-lamport)
                 (inc current))))
      ;; Using condp for this is weak. Should probably use a defmethod,
      ;; at least. Or possibly even something like bidi.
      ;; What I remember about core.match seems like overkill, but it
      ;; also seems tailor-made for this. Assuming it is available in
      ;; cljs.
      (condp = action
        :frereth/ack-forked
        (if-let [{:keys [::worker]} (do-get-world world-key ::forked)]
          (try
            (adjust-world-state! world-key ::forked ::active)
            (catch :default ex
              (console.error "Forked ACK failed to adjust world state:" ex)))
          (console.error "Missing forked worker"
                         {::problem envelope
                          ::forked (get-worlds-by-state ::forked)
                          ::world-id world-key}))


        :frereth/ack-forking
        (try
          (if-let [ack-chan (::waiting-ack (do-get-world world-key
                                                         ::pending))]
            (let [success (async/put! ack-chan body)]
              (console.log (str "Message put onto " ack-chan
                                ": " success)))
            (console.error "ACK about non-pending world"
                           {::problem envelope
                            ::pending (get-worlds-by-state ::pending)
                            ::world-id world-key}))
          (catch :default ex
            (console.error "Failed to handle :frereth/ack-forking" ex body)))

        :frereth/disconnect
        (if-let [worker (::worker (do-get-world world-key))]
          (.postMessage worker raw-envelope)
          (console.error "Disconnect message for"
                         world-key
                         "in"
                         envelope
                         ". No match in"
                         @worlds))

        :frereth/forward
        (if-let [world (do-get-world world-key ::active)]
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
          (console.error "Missing world" world-key "inside" worlds)))))

  (defn send-message!
    "Send `body` over `socket` for `world-id`"
    [socket
     world-id
     body]
    (swap! lamport inc)
    (let [envelope {:frereth/body body
                    :frereth/lamport @lamport
                    :frereth/wall-clock (.now js/Date)
                    :frereth/world world-id}]
      ;; TODO: Check that bufferedAmount is low enough
      ;; to send more
      (try
        (println "Trying to send-message!" envelope)
        (.send socket (serialize envelope))
        (println body "sent successfully")
        (catch :default ex
          (console.error "Sending message failed:" ex))))))

(defn event-forwarder
  "Sanitize event and post it to Worker"
  [worker ctrl-id tag]
  ;; Q: Is it worth keeping these around long-term?
  ;; Or better to just create/discard them on the fly as needed?
  (let [writer (transit/writer :json)]
    (fn [event]
      ;; TODO: Window manager needs to set up something so events
      ;; don't go to unfocused windows.
      ;; Those really need some kind of extra styling to show an
      ;; inactive overlay. The click (or mouse-over...whichever
      ;; matches the end-user's preferences) should transfer
      ;; focus.
      (console.info "Posting" tag "to web worker for" ctrl-id)
      (let [ks (.keys js/Object event)
            ;; Q: Would this be worth using a transducer?
            ;; Or possibly clojure.walk?
            pairs (map (fn [k]
                         (let [v (aget event k)]
                           ;; Only transfer primitives
                           (when (some #(% v)
                                       #{not
                                         boolean?
                                         number?
                                         string?
                                         ;; clojurescript has issues with the
                                         ;; js/Symbol primitive.
                                         ;; e.g. https://dev.clojure.org/jira/browse/CLJS-1628
                                         ;; For now, skip them.
                                         })
                             [k v])))
                       ks)
            pairs (filter identity pairs)
            clone (into {} pairs)]
        ;; Q: How do I indicate that this has been handled?
        ;; In greater depth, how should the Worker indicate that it has
        ;; handled the event, so this can do whatever's appropriate with
        ;; it (whether that's cancelling it, stopping the bubbling, or
        ;; whatever).
        (.postMessage worker (transit/write writer
                                            {:frereth/action :frereth/event
                                             :frereth/body [tag ctrl-id clone]}))))))

(defn on-*-replace
  [worker ctrl-id acc [k v]]
  (let [s (name k)
        prefix (subs s 0 3)]
    (assoc acc k
           (if (= "on-" prefix)
             (event-forwarder worker ctrl-id v)
             v))))

(defn sanitize-scripts
  "Convert scripting events to messages that get posted back to worker"
  [worker
   [ctrl-id attributes & body :as raw-dom]]
  ;; Need to walk the tree to find any/all scripting components
  ;; This is fine for plain "Form-1" components (as defined in
  ;; https://purelyfunctional.tv/guide/reagent) that just
  ;; return plain Hiccup.
  ;; It breaks down with "Form-2" functions that return functions.
  ;; It completely falls apart for "Form-3" complex components.

  ;; It's very tempting to just give up on any of the main
  ;; react wrappers and either write my own (insanity!) or
  ;; see if something like Matt-Esch/virtual-dom could possibly work.
  (when ctrl-id
    (let [prefix (into [ctrl-id]
                       (when (map? attributes)
                         [(reduce
                           (partial on-*-replace worker ctrl-id)
                            {}
                            attributes)]))]
      ;; TODO: Also have to block iframes
      ;; TODO: What other pieces could make life awful?
      ;; Q: How do I want to handle CSS?
      (into prefix
            (let [body
                  (if (map? attributes)
                    body
                    (into [attributes] body))]
              (map (fn [content]
                     (if (vector? content)
                       (sanitize-scripts worker content)
                       content))
                   body))))))

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
               :socket ::web-socket
               :session-id ::session-id
               :key-pair ::internal-key-pair
               ;; Actually, this is anything that transit can serialize
               :public ::jwk
               :cookie ::cookie)
  :ret (s/nilable ::web-worker))
(defn spawn-worker!
  "Begin promise chain that leads to an ::active World"
  [crypto
   socket
   session-id
   {:keys [::secret]}
   world-key
   cookie]
  (when window.Worker
    (console.log "Constructing Worker fork URL based on" @base-url
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
    (let [url (url/url @base-url "/api/fork")
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
                       (partial on-worker-message socket world-key worker))
                 (set! (.-onerror worker)
                       (fn [problem]
                         (console.error "FIXME: Need to handle"
                                        problem
                                        "from World"
                                        world-key)))
                 worker))))))

(s/fdef do-build-actual-worker
  :args (s/cat :ch ::async-chan
               :spawner ::work-spawner
               :raw-key-pair ::key-pair
               :full-pk ::public-key)
  :ret ::async-chan)
(defn do-build-actual-worker
  [response-ch spawner raw-key-pair full-pk]
  (go
    ;; FIXME: Use alts! with a timeout
    ;; Don't care what the actual response was. The dispatcher should
    ;; have filtered that before writing to this channel.
    ;; Although, realistically, the response should include something
    ;; like the public key for this World on the Client. Or at least on
    ;; the web server.
    (let [response (async/<! response-ch)
          cookie (:frereth/cookie response)]
      (console.log "Received signal to fork worker:" response)
      (if-let [worker-promise (spawner cookie)]
        (.then worker-promise
               ;; The comment below is mostly speculation from when I was
               ;; designing this on the fly.
               ;; TODO: Clean it up and move it into documentation
               (fn [worker]
                 ;; Honestly, that should be something that gets
                 ;; assigned here. It's independent of whatever key the
                 ;; Worker might actually use.
                 ;; It's really just for routing messages to/from that
                 ;; Worker.
                 ;; Q: How about this approach?
                 ;; We query the web server for a shell URL. Include our
                 ;; version of the worker ID as a query param.
                 ;; Q: Worth using the websocket for that? (it doesn't
                 ;; seem likely).
                 ;; The web server creates a pending-world (keyed to
                 ;; our worker-id), and
                 ;; its response includes the actual URL to load that
                 ;; Worker.
                 ;; Then each Worker could be responsible for tracking
                 ;; its own signing key.
                 ;; Except that sharing the responsibility would be
                 ;; silly.
                 ;; Want the signing key out here at this level, so we
                 ;; don't have to duplicate the signing code
                 ;; everywhere.
                 ;; So, generate the short-term key pair here. Send
                 ;; the public half to the web server for validation.
                 ;; Possibly sign that request with the Session key.
                 ;; Then use the public key to dispatch messages.
                 (adjust-world-state! full-pk
                                      ::pending
                                      ::forking
                                      (fn [current]
                                        (into (dissoc current ::waiting-ack)
                                              {::cookie cookie
                                               ::key-pair raw-key-pair
                                               ::worker worker})))))
        (.warn js/console "Spawning shell failed")))))

(s/fdef build-worker-from-exported-key
  :args (s/cat :socket ::web-socket
               :spawner ::work-spawner
               :raw-key-pair ::key-pair)
  ;; Returns a core.async channel
  :ret any?)
(defn build-worker-from-exported-key
  "Spawn worker based on newly exported public key."
  [socket spawner raw-key-pair public]
  (let [signing-key (atom nil)
        full-pk (js->clj public)
        ch (async/chan)
        ;; This seems like it's putting the cart before the horse, but
        ;; it returns a go block that's waiting for something
        ;; to write to ch to trigger the creation of the WebWorker.
        actual-work (do-build-actual-worker ch
                                            spawner
                                            raw-key-pair
                                            full-pk)]
    (console.log "cljs JWK:" full-pk)
    ;; Q: Is this worth a standalone function?
    (swap! worlds
           assoc
           full-pk
           {::waiting-ack ch
            ::state ::pending})
    (console.log "Set up pending World. Notify about pending fork.")
    (send-message! socket full-pk {:frereth/action :frereth/forking
                                   :frereth/command 'shell
                                   :frereth/pid full-pk})
    (reset! signing-key full-pk)))

(s/fdef fork-login!
  :args (s/cat :socket ::web-socket
               :session-id ::session-id)
  :ret any?)
(defn fork-login!
  "TODO: Auth. Probably using SRP."
  [socket session-id]
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
  (send-message! socket ::login session-id))

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
  [socket session-id crypto key-pair]
  (let [raw-secret (.-privateKey key-pair)
        raw-public (.-publicKey key-pair)
        exported (.exportKey crypto "jwk" raw-public)]
    (.then exported
           (fn [public]
             (build-worker-from-exported-key
              socket
              (partial spawn-worker!
                       crypto
                       socket
                       session-id
                       {::public raw-public
                        ::secret raw-secret}
                       (js->clj public))
              key-pair
              public)))))

(defn fork-shell!
  [socket session-id]
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
                                        socket
                                        session-id
                                        crypto))))

(defn connect-web-socket!
  [shell-forker session-id]
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
              (swap! shared-socket
                     (fn [existing]
                       (when existing
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
      (set! (.-onclose ws)
            (fn [event]
              (console.warn "Frereth Connection closed:" event)))
      (set! (.-onerror ws)
            (fn [event]
              (console.error "Frereth Connection error:" event))))
    (catch :default ex
      (console.error ex))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn start! []
  (println "Starting the app")

  (when-not (repl/alive?)
    (println "Trying to connect REPL websocket")
    (repl/connect "ws://localhost:9001"))

  (connect-web-socket! fork-shell! session-id-from-server))

;; FIXME: This seems as though it should be protected behind the
;; equivalent of defonce
(when js/window
  (start!))

(comment
  ;; Macro test
  (foobar :abc 3)

  ;; Example of interop call to plain JS in src/cljs/foo.js
  (js/foo)

  (println "Console print check"))
