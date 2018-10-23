(ns frontend.core
  (:require-macros [frontend.macro :refer [foobar]])
  (:require [cognitect.transit :as transit]
            [foo.bar]
            ;; Start by at least partially supporting this, since it's
            ;; so popular
            [reagent.core :as r]
            [clojure.spec.alpha :as s]
            [clojure.string]
            [weasel.repl :as repl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)

(def idle-worker-pool
  "Workers were designed to be heavyweight. How dangerous to pool?"
  (atom []))
(comment
  (println idle-worker-pool)
  (println (cljs->js @idle-worker-pool))
  (console.log (-> idle-worker-pool deref first))
  (.postMessage (-> idle-worker-pool deref first) ::abc)
  )

(def secret-key
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
(def shared-socket (atom nil))

;; Map of world-keys to WebWorkers
(def worlds (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn event-forwarder
  "Sanitize event and post it to Worker"
  [worker ctrl-id tag]
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
      (.postMessage worker (pr-str [tag ctrl-id clone])))))

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

(defn spawn-worker
  []
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
    (let [worker (new window.Worker
                      "/js/worker.js"
                      ;; Currently redundant:
                      ;; in Chrome, at least, module scripts are not
                      ;; supported on DedicatedWorker
                      #js{"type" "classic"})]
      (set! (.-onmessage worker)
            (fn [event]
              (let [new-dom-wrapper (.-data event)
                    raw-dom (cljs.reader/read-string new-dom-wrapper)
                    dom (sanitize-scripts worker raw-dom)]
                (r/render-component [(constantly dom)]
                                    (js/document.getElementById "app")))))
      worker)))

(defn fork-shell!
  []
  (let [crypto (.-subtle (.-crypto js/window))
        ;; Q: Any point to encrypting?
        ;; Q: Are these settings reasonable?
        ;; As a first step, I really just want a randomly-generated
        ;; public key that I can use as a PID to make it difficult for
        ;; other "processes" to interfere.
        signing-key-promise (.generateKey crypto
                                          (clj->js {:name "ECDSA"
                                                    "namedCurve" "P-384"})
                                          true
                                          (clj->js ["sign"
                                                    "verify"]))
        signing-key (atom nil)]
    (.then signing-key-promise (fn [generated]
                                 (reset! signing-key generated)))
    (if-let [worker (spawn-worker)]
      (do
        (comment
          ;; The worker pool is getting ahead of myself.
          ;; Part of the missing Window Manager abstraction
          ;; mentioned above. Assuming that it makes any sense
          ;; at all.
          ;; It seems like a good optimization, but breaking
          ;; the isolation we get from web workers may not be
          ;; worth any theoretical gain.
          (swap! idle-worker-pool conj spawn-worker))
        ;; FIXME: Need to define worker-key
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
        (swap! worlds assoc signing-key worker)
        ;; TODO: Need to notify the Client that this World is ready to interact
        (.log js/console "Shell spawned"))
      (.warn js/console "Spawning shell failed"))))

(defn connect-web-socket!
  [fork-shell!]
  (console.log "Connecting WebSocket")
  (try
    ;; TODO: Build this from window.location
    ;; c.f. https://stackoverflow.com/questions/10406930/how-to-construct-a-websocket-uri-relative-to-the-page-uri
    (let [location (.-location js/window)
          origin-protocol (.-protocol location)
          protocol-length (count origin-protocol)
          protocol (if (= \s
                          (clojure.string/lower-case (aget origin-protocol
                                                           (- protocol-length 2))))
                     "wss"
                     "ws")
          base-url (str protocol "://" (.-host location))
          url (str base-url  "/ws")
          ws (js/WebSocket. url)
          writer (transit/writer :json)]
      ;; Q: Does "arraybuffer" make any sense here?
      (set! (.-binaryType ws) "blob")
      ;; Q: Worth using a library to wrap the details?
      (set! (.-onopen ws)
            (fn [event]
              (console.log event)
              ;; Probably reasonable to base this on something like the
              ;; CurveCP handshake.
              ;; Honestly, the server could/should inject a new key-pair
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
              (.send ws (transit/write writer secret-key))
              (reset! shared-socket ws)
              ;; This is where things like deferreds, core.async,
              ;; and promises come in handy.
              ;; Once the login sequence has completed, we want to spin
              ;; up the top-level shell (which, in this case, is our
              ;; log-viewer Worker)
              (fork-shell!)))
      ;; Q: msgpack ?
      ;; A: Not in clojurescript, yet.
      (let [reader (transit/reader :json)]
        (set! (.-onmessage ws)
              (fn [event]
                (console.log event)
                (let [raw-envelope (.-data event)
                      envelope (transit/read reader raw-envelope)
                      world-key (:frereth/world envelope)
                      worker (get @worlds world-key)]
                  (if worker
                    (let [body (:frereth/body envelope)]
                      (.postMessage worker body))
                    (console.error "Message for"
                                   world-key
                                   "in"
                                   envelope
                                   ". No match in"
                                   (keys @worlds)))))))
      (set! (.-onclose ws)
            (fn [event]
              (console.error "Server closed connection:" event))))
    (catch :default ex
      (console.error ex))))

(defn start! []
  (js/console.log "Starting the app")

  (when-not (repl/alive?)
    (repl/connect "ws://localhost:9001"))

  (connect-web-socket! fork-shell!))

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
