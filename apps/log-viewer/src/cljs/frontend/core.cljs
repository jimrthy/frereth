(ns frontend.core
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [frontend.macro :refer [foobar]])
  (:require [cognitect.transit :as transit]
            [foo.bar]
            ;; Start by at least partially supporting this, since it's
            ;; so popular
            [reagent.core :as r]
            [cljs.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string]
            [weasel.repl :as repl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: Define these
(s/def ::async-chan any?)
(s/def ::key-pair any?)
(s/def ::public-key any?)
(s/def ::session-id any?)
(s/def ::web-socket any?)
(s/def ::web-worker any?)

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
(def worlds (atom {::active {}
                   ::pending {}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn array-buffer->string
  [bs]
  (let [data-view (js/DataView. bs)
        ;; Q: What encoding is appropriate here?
        decoder (js/TextDecoder. "utf-8")]
    (.decode decoder data-view)))

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

(s/fdef spawn-worker
  ;; TODO: Need specs for these args
  :args (s/cat :session-id any?
               :public-key any?))
(defn spawn-worker
  [session-id public-key]
  (when window.Worker
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
    (let [encoded-key (js/encodeURIComponent public-key)
          worker (new window.Worker
                      (str "/js/worker.js?world-key=" encoded-key "&session-id=" session-id)
                      #_(str "/shell?world-key=" encoded-key "&session-id=" session-id)
                      ;; Currently redundant:
                      ;; in Chrome, at least, module scripts are not
                      ;; supported on DedicatedWorker
                      #js{"type" "classic"})]
      (set! (.-onmessage worker)
            ;; This needs to be fleshed out.
            ;; Sure, the worker can send us renderer events.
            ;; But that's the tip of the iceberg.
            ;; It also needs to send a notification to the server
            ;; that it's ready to start doing work (:frereth/forked,
            ;; so its ::state switches from ::pending to ::active).
            ;; It might even be legal to have a full-blown message
            ;; bus here so workers can communicate among each other,
            ;; though that seems risky.
            ;; FIXME: Start back here
            (fn [event]
              (let [data (.-data event)]
                (try
                  (let [{:keys [:frereth/action]
                         :as raw-message} (transit/read (transit/reader :json)
                                                        data)]
                    (console.log "Message from worker:" action)
                    (condp = action
                      :frereth/render
                      (let [dom (sanitize-scripts worker
                                                  (:frereth/body raw-message))]
                        (r/render-component [(constantly dom)]
                                            (js/document.getElementById "app")))
                      :frereth/forked (send-message! @shared-socket
                                                     public-key
                                                     data)))
                  (catch :default ex
                    (console.error ex "Trying to deserialize" data))))))
      worker)))

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

  (defn recv-message!
    [event]
    (console.log (str "Incoming at "
                      (.now js/Date)
                      ": "
                      event))
    (let [raw-envelope (array-buffer->string (.-data event))
          _ (console.log "Trying to read" raw-envelope)
          envelope (transit/read reader raw-envelope)
          {:keys [:frereth/action
                  :frereth/body
                  :frereth/world-id]
           ;; Not all messages will be about a World's Worker.
           ;; Q: Will they?
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
        :frereth/forward
        (let [worker (-> worlds
                         deref
                         ::active
                         (get world-id)
                         ::worker)]
          (if worker
            (.postMessage worker body)
            (console.error "Message for"
                           world-id
                           "in"
                           envelope
                           ". No match in"
                           (keys @worlds))))
        :frereth/ack-forking
        (try
          (let [ack-chan (-> worlds
                             deref
                             ::pending
                             (get world-id))]
            (if ack-chan
              (let [success (async/put! ack-chan body)]
                ;; This acts like it works, but it doesn't.
                ;; FIXME: Start back here.
                (console.log (str "Message put onto " ack-chan
                                  ": " success)))
              (console.error "ACK about non-pending world"
                             {::problem envelope
                              ::pending (::pending @worlds)
                              ::world-id world-id})))
          (catch :default ex
            (console.error "Failed to handle :frereth/ack-forking" ex body))))))

  (defn send-message!
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
        (println "Trying to send-message!")
        (.send socket (transit/write writer envelope))
        (println body "sent successfully")
        (catch :default ex
          (console.error "Sending message failed:" ex))))))

(s/fdef do-build-actual-worker
  :args (s/cat :ch ::async-chan
               :spawner (s/fspec :args (s/cat :session-id ::session-id
                                              :public-key ::public-key)
                                 :ret ::web-worker)
               :session-id ::session-id
               :raw-key-pair ::key-pair
               :full-pk ::public-key)
  :ret ::async-chan)
(defn do-build-actual-worker
  [response-ch spawner session-id raw-key-pair full-pk]
  (go
    ;; FIXME: Use alts! with a timeout
    ;; Don't care what the actual response was. The dispatcher should
    ;; have filtered that before writing to this channel.
    ;; Although, realistically, the response should include something
    ;; like the public key for this World on the Client. Or at least on
    ;; the web server.
    (let [_ (async/<! response-ch)]
      (console.log "Received signal to spawn worker")
      (if-let [worker (spawner session-id full-pk)]
        (do
          (comment
            ;; The worker pool is getting ahead of myself.
            ;; Part of the missing Window Manager abstraction
            ;; mentioned above. Assuming that it makes any sense
            ;; at all.
            ;; It seems like a good optimization, but breaking
            ;; the isolation we get from web workers may not be
            ;; worth any theoretical gain.
            (swap! idle-worker-pool conj (partial spawn-worker session-id)))
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
          (swap! worlds
                 (fn [all]
                   (let [no-longer-pending (update all ::pending
                                                   #(dissoc % full-pk))]
                     (assoc-in no-longer-pending
                               [::active full-pk]
                               {::key-pair raw-key-pair
                                ::worker worker})))
                 ;; TODO: Need to notify the Client that this World is ready to interact
                 (.log js/console "Shell spawned")))
        (.warn js/console "Spawning shell failed")))))

(s/fdef build-worker-from-exported-key
  ;; TODO: Specs!
  :args (s/cat :socket any?  ; websocket
               :session-id any?  ; Q: What is this?
               :spawner (s/fspec :args (s/cat :session-id any?
                                              :full-pk any?))
               :raw-key-pair any? ; Something like crypto/KeyPair
               ;; Something like crypto/Key
               :public any?)
  ;; Returns a core.async channel
  :ret any?)
(defn build-worker-from-exported-key
  "Spawn worker based on newly exported public key."
  [socket session-id spawner raw-key-pair public]
  (let [signing-key (atom nil)
        full-pk (js->clj public)
        ch (async/chan)
        ;; This seems out of order.
        ;; We *do* want to set everything up to do the work,
        ;; but we also don't want to spawn the WebWorker
        ;; until we've received the ::forking-ack.
        ;; At the same time, we do want to set up a placeholder
        ;; to provide feedback that something is happening.
        ;; But this returns a go block that's waiting for something
        ;; to write to ch to trigger the creation of the WebWorker.
        actual-work (do-build-actual-worker ch
                                         spawner
                                         session-id
                                         raw-key-pair
                                         full-pk)]
    (console.log "cljs JWK:" full-pk)
    (swap! worlds
           (fn [worlds]
             (update worlds ::pending
                     (fn [pending]
                       (assoc pending full-pk ch)))))
    (send-message! socket full-pk {:frereth/action :frereth/forking
                                   :frereth/command 'shell
                                   :frereth/pid full-pk})
    (reset! signing-key full-pk)))

(s/fdef fork-login!
  :args (s/cat :socket ::web-socket
               :session-id ::session-id)
  :ret any?)
(defn fork-login!
  "Returns "
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

(s/fdef export-key-and-build-worker!
  ;; FIXME: Spec these args
  :args (s/cat :socket any?
               :session-id any?
               :crypto any?
               :key-pair any?)
  ;; It's tempting to spec that this returns a future.
  ;; But that's just an implementation detail. This is
  ;; called for side-effects.
  :ret any?)
(defn export-key-and-build-worker!
  [socket session-id crypto key-pair]
  (let [secret (.-privateKey key-pair)
        raw-public (.-publicKey key-pair)
        exported (.exportKey crypto "jwk" raw-public)]
    (.then exported (partial build-worker-from-exported-key
                             socket
                             session-id
                             spawn-worker
                             key-pair))))

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
    (.then signing-key-promise (partial export-key-and-build-worker!
                                        socket
                                        session-id
                                        crypto))))

(defn connect-web-socket!
  [shell-forker session-id]
  (console.log "Connecting WebSocket")
  (try
    (let [location (.-location js/window)
          origin-protocol (.-protocol location)
          protocol-length (count origin-protocol)
          protocol (if (= \s
                          (clojure.string/lower-case (aget origin-protocol
                                                           (- protocol-length 2))))
                     "wss"
                     "ws")
          local-base-url (str protocol "://" (.-host location))
          url (str local-base-url  "/ws")
          ws (js/WebSocket. url)]
      ;; This actually isn't very useful.
      ;; Although it might be worth saving a format string that lets us
      ;; swap out the protocol. The websocket connection string really
      ;; doesn't make any sense after we're done here. But we'll still
      ;; need lots of XHR interaction for things like Web Workers.
      (reset! base-url local-base-url)
      ;; A blob is really just a file handle. Have to jump through an
      ;; async op to convert it to an arraybuffer.
      (set! (.-binaryType ws) "arraybuffer")
      ;; Q: Worth using a library like sente or haslett to wrap the
      ;; details?
      (set! (.-onopen ws)
            (fn [event]
              (console.log "Websocket opened:" event ws)
              (reset! shared-socket ws)

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
              (console.warn "Connection closed:" event)))
      (set! (.-onerror ws)
            (fn [event]
              (console.error "Connection error:" event))))
    (catch :default ex
      (console.error ex))))

(defn start! []
  (js/console.log "Starting the app")

  (when-not (repl/alive?)
    (repl/connect "ws://localhost:9001"))

  (connect-web-socket! fork-shell! session-id-from-server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(when js/window
  (start!))

(comment
  ;; Macro test
  (foobar :abc 3)

  ;; Example of interop call to plain JS in src/cljs/foo.js
  (js/foo)

  (println "Console print check"))
