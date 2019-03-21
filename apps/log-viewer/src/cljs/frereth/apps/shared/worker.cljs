(ns frereth.apps.shared.worker
  "Handle interactions with the various web workers"
  (:require-macros
   [cljs.core.async.macros :refer [go]])
  (:require
   [cemerick.url :as url]
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.socket :as web-socket]
   [frereth.apps.shared.specs :as specs]
   [frereth.apps.shared.world :as world]
   ;; Start by at least partially supporting this, since it's
   ;; so popular
   [reagent.core :as r]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::web-worker #(= js/Worker (type %)))

(s/def ::worker-instance (s/keys :req [:frereth/cookie
                                       ::web-worker]))

;; Q: What is this really?
(s/def ::exported-public-key any?)

(s/def ::worker-map (s/map-of :frereth/world-id ::worker-instance))

(s/def ::workers (s/and #(instance? Atom %)
                        #(s/valid? ::worker-map (deref %))))

(s/def ::manager (s/keys :req [::lamport/clock
                               ::session/manager
                               ::web-socket/wrapper
                               ::workers]))

(defmulti handle-worker-message
  "Cope with message from Worker"
  ;; Q: Is this even worth using a multi-method?
  ;; It's very tempting to use a 1-way Pedestal event chain
  (fn [this action world-key worker event data]
    action))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defn event-forwarder
  "Sanitize event and post it to Worker"
  [worker ctrl-id tag]
  ;; Q: Is it worth keeping these around long-term?
  ;; Or better to just create/discard them on the fly as needed?
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
      (.postMessage worker (serial/serialize
                                          {:frereth/action :frereth/event
                                           :frereth/body [tag ctrl-id clone]})))))

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

(s/fdef send-message!
  :args (s/cat :this ::manager
               ;; should be bytes? but cljs doesn't have the concept
               ;; Q: Is it a string?
               ;; A: Actually, it should be a map of a JWK
               :world-id any?
               ;; Anything that transit can serialize natively, anyway
               :body any?))
(defn send-message!
  "Send `body` over `socket` for `world-id`"
  [{{:keys [::web-socket/socket]} ::web-socket/wrapper
    :keys [::lamport/clock]
    :as this}
   world-id
   body]
  (when-not clock
    (throw (ex-info "Missing clock"
                    {::problem this})))
  (lamport/do-tick clock)
  (let [envelope {:frereth/body body
                  :frereth/lamport @clock
                  :frereth/wall-clock (.now js/Date)
                  :frereth/world world-id}]
    ;; TODO: Check that bufferedAmount is low enough
    ;; to send more
    (try
      (println "Trying to send-message!" envelope)
      (.send socket (serial/serialize envelope))
      (println body "sent successfully")
      (catch :default ex
        (console.error "Sending message failed:" ex)))))

(defmethod handle-worker-message :default
  [{:keys [:web-socket/wrapper]
    :as this}
   action world-key worker event raw-message]
  (console.error "Unhandled action:" action
                 "\nfrom world:" world-key))

(defmethod handle-worker-message :frereth/forked
  [{:keys [::web-socket/wrapper
           ::session/manager]
    :as this}
   action world-key worker event raw-message]
  (let [worlds (session/get-worlds manager)
        {:keys [::world/cookie]
         :as world-state} (world/get-world worlds
                                           world-key)]
    (if cookie
      (let [decorated (assoc raw-message
                             :frereth/cookie cookie
                             :frereth/pid world-key)
            {:keys [::web-socket/socket]} wrapper]
        (send-message! this
                       world-key
                       ;; send-message! will serialize this again.
                       ;; Which seems wasteful.
                       ;; Would be better to just have this :action as
                       ;; a tag, followed by the body.
                       ;; Except that we have added details to the value
                       #_data
                       ;; Anyway, that's premature optimization.
                       ;; Worry about that detail later.
                       ;; Even though this is the boundary area where
                       ;; it's probably the most important.
                       ;; (Well, not here. But in general)
                       decorated)
        (session/do-mark-forked manager world-key worker))
      (console.error "Missing ::cookie among" world-state
                     "\namong" (keys worlds)
                     "\nin" worlds
                     "\nfrom" this))))

(defmethod handle-worker-message :frereth/render
  [this action world-key worker event raw-message]
  (let [dom (sanitize-scripts worker
                              (:frereth/body raw-message))]
    (r/render-component [(constantly dom)]
                        (js/document.getElementById "app"))))

(s/fdef on-worker-message
  :args (s/cat :this ::manager
               :world-key :frereth/world-key
               :worker ::worker-instance
               ;; Q: What is this?
               :event any?))
(defn on-worker-message
  "Dispatch message from Worker"
  [{:keys [::web-socket/wrapper]
    :as this}
   world-key worker event]
  (let [raw-data (.-data event)
        ;; This is dubious:
        ;; We're calling deserialize here to get the dispatch
        ;; value.
        ;; However: the actual implementation method is almost
        ;; required to call it again to get to any data.
        ;; TODO: Move the deserialization up to the caller.
        ;; Or, more realistically, make this a simple public
        ;; function that does the deserialization and then
        ;; calls a multimethod that can just dispatch on
        ;; :frereth/action
        {:keys [:frereth/action]
         :as data} (serial/deserialize raw-data)]
    (console.log "Message from" worker ":" action)
    (handle-worker-message this action world-key worker event data)))

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
           ::web-socket/wrapper]
    :as this}
   session-id
   {:keys [::secret]}
   world-key
   cookie]
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
    (let [initiate {:frereth/cookie cookie
                    :frereth/session-id session-id
                    :frereth/world-key world-key}
          packet-string (serial/serialize initiate)
          packet-bytes (serial/str->array packet-string)
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
               (let [base-url (::web-socket/base-url wrapper)
                     sock (::web-socket/socket wrapper)
                     path-to-shell (::session/path-to-fork manager)
                     url (url/url base-url path-to-shell)
                     params {:frereth/initiate packet-string
                             :frereth/signature (-> signature
                                                    serial/array-buffer->string
                                                    serial/serialize)}
                     _ (console.log "Constructed Worker fork URL based on\n"
                                    base-url "\namong" wrapper
                                    "\nadded" path-to-shell
                                    "\nfrom session-manager" manager
                                    "\nTrying to trigger worker on\n"
                                    url
                                    "\nwith query params:\n"
                                    params
                                    "\nsigned with secret-key" secret)
                     ;; Web server uses the cookie to verify that
                     ;; the request comes from the correct World.
                     ;; Q: Can I use window.Worker. instead of `new`?
                     worker (new window.Worker
                                 (str (assoc url :query params))
                                 ;; Currently redundant:
                                 ;; in Chrome, at least, module scripts are not
                                 ;; supported on DedicatedWorker
                                 #js{"type" "classic"})]
                 (set! (.-onmessage worker)
                       (partial on-worker-message this world-key worker))
                 (set! (.-onerror worker)
                       (fn [problem]
                         (console.error "FIXME: Need to handle\n"
                                        problem
                                        "\nfrom World\n"
                                        world-key)))
                 worker))))))

(s/fdef do-build-actual-worker
  :args (s/cat :this ::manager
               :ch ::specs/async-chan
               :spawner ::work-spawner
               :raw-key-pair ::key-pair
               :full-pk ::public-key)
  :ret ::specs/async-chan)
(defn do-build-actual-worker
  [{:keys [::session/manager]
    :as this}
   response-ch spawner raw-key-pair full-pk]
  (go
    ;; Don't care what the actual response was. The dispatcher should
    ;; have filtered that before writing to this channel.
    ;; Although, realistically, the response should include something
    ;; like the public key for this World on the Client. Or at least on
    ;; the web server.
    (let [[response port] (async/alts! [response-ch (async/timeout 1000)])]
      (if response
        (do
          (assert (= port response-ch))
          (let [cookie (:frereth/cookie response)]
            (console.log "Received signal to fork worker:" response
                         "\nCalling session/do-mark-forking to set cookie")
            (try
              (session/do-mark-forking manager
                                       full-pk
                                       cookie
                                       raw-key-pair)
              (try
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

                           ;; A (to the question in log message): No. This happens
                           ;; before the World script gets run.
                           (console.warn "Q: Would this be a better place to call session/do-mark-forked ?")))
                  (.warn js/console "Spawning shell failed"))
                (catch :default ex
                  (console.error ex
                                 "\nUnhandler error spawnin worker")))
              (catch :default ex
                (console.error ex
                               "\nTrying to mark world as forking starting in\n"
                               manager)))))
        (.error js/console "Timed out waiting for forking ACK")))))

(s/fdef build-worker-from-exported-key
  :args (s/cat :this ::manager
               :spawner ::work-spawner
               :raw-key-pair ::key-pair
               :exported-pk ::exported-public-key)
  :ret any?)
(defn build-worker-from-exported-key
  "Spawn worker based on newly exported public key."
  [{:keys [::web-socket/wrapper
           ::session/manager]
    :as this}
   spawner raw-key-pair full-pk]
  (console.log "Set up pending World. Notify about pending fork.")
  ;; Want this part to happen asap to minimize the length of time
  ;; we have to spend waiting on it
  (send-message! this full-pk {:frereth/action :frereth/forking
                               :frereth/command 'shell
                               :frereth/pid full-pk})
  (console.log "Setting up worker 'fork' in worlds inside the manager in"
               this "\namong" (keys this))
  ;; Q: Should this next section wait on the forking-ACK?
  ;; (It's probably worth mentioning in that regard that, originally,
  ;; this got called before we called send-message!)
  (let [worlds (session/get-worlds manager)
        ch (async/chan)]
    (console.log "cljs JWK:" full-pk
                 "\nworlds: " worlds)
    (session/add-pending-world manager full-pk ch {})
    ;; This seems like it's putting the cart before the horse, but
    ;; it returns a go block that's waiting for something
    ;; to write to ch to trigger the creation of the WebWorker.
    ;; It seems like we should probably do something with it, but
    ;; I'm not sure what would be appropriate.
    (do-build-actual-worker this
                            ch
                            spawner
                            raw-key-pair
                            full-pk)))

(s/fdef export-public-key!
  :args (s/cat :this ::manager
               :session-id ::session-id
               ;; FIXME: Spec this
               :crypto any?
               :key-pair ::key-pair)
  :ret js/Promise)
(defn export-public-key!
  [{{:keys [::web-socket/socket]} ::web-socket/wrapper
    :as this}
   session-id crypto key-pair]
  (let [raw-secret (.-privateKey key-pair)
        raw-public (.-publicKey key-pair)]
    (.exportKey crypto "jwk" raw-public)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef fork-shell!
  :args (s/cat :this ::manager)
  :ret any?)
(defn fork-shell!
  [this
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
                                               "verify"])
        key-pair-atom (atom nil)
        export-pk-promise (.then signing-key-promise
                                 (fn [key-pair]
                                   (reset! key-pair-atom key-pair)
                                   (export-public-key!
                                    this
                                    session-id
                                    crypto
                                    key-pair)))]
    (.then export-pk-promise (fn [exported]
                               (let [key-pair @key-pair-atom
                                     raw-public (.-publicKey key-pair)
                                     raw-secret (.-privateKey key-pair)
                                     clj-exported (js->clj exported)]
                                 (build-worker-from-exported-key
                                  this
                                  (partial spawn-worker!
                                           crypto
                                           this
                                           session-id
                                           {::public raw-public
                                            ::secret raw-secret}
                                           clj-exported)
                                  @key-pair-atom
                                  clj-exported))))))

(s/fdef manager
  :args (s/cat :clock ::lamport/clock
               :session-manager ::session/manager
               :web-sock-wrapper ::web-socket/wrapper)
  :ret ::manager)
(defn manager
  [clock session-manager web-sock-wrapper]
  {:pre [clock session-manager web-sock-wrapper]}
  {::lamport/clock clock
   ::session/manager session-manager
   ::web-socket/wrapper web-sock-wrapper
   ::workers (atom {})})
