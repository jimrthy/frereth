(ns frereth.apps.shared.worker
  "Handle interactions with the various web workers"
  (:require-macros
   [cljs.core.async.macros :refer [go]])
  (:require
   #_[cemerick.url :as url]
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.connection]  ; for specs
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.serialization :as serial]
   [frereth.apps.shared.session :as session]
   [frereth.apps.shared.socket :as web-socket]
   [frereth.apps.shared.specs :as specs]
   [frereth.apps.shared.world :as world]
   [integrant.core :as ig]
   ;; Start by at least partially supporting this, since it's
   ;; so popular
   #_[reagent.core :as r]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::web-worker #(instance? js/Worker %))
(s/def ::dom-event #(instance? js/Event %))

(s/def ::worker-instance (s/keys :req [:frereth/cookie
                                       ::web-worker]))

;; This tracks whether we need to call rAF to forward
;; frame increments to each worker.
;; My initial approach seems broken. I ought to be able
;; to tell whether the Web Worker has a requestAnimationFrame member,
;; at least after I create the original.
;; Q: Right?
;; I'm torn about whether this belongs in here.
;; On one hand, of course it does! Who else makes sense as a source of
;; truth about a Worker's capabilities?
;; On the other...why even make it an option? The window manager gets to
;; trigger animations because it owns which windows are visible/active.
(s/def ::workers-need-dom-animation? (s/and
                              #(instance? Atom %)
                              #(boolean? (deref %))))

;; Q: What is this, really?
(s/def ::exported-public-key any?)

(s/def ::worker-map (s/map-of :frereth/world-id ::worker-instance))

(s/def ::workers (s/and #(instance? Atom %)
                        #(s/valid? ::worker-map (deref %))))

(s/def ::manager (s/keys :req [::lamport/clock
                               ::session/manager
                               ::web-socket/wrapper
                               ::workers
                               ::workers-need-dom-animation?]))

(defmulti handle-worker-message
  "Cope with message from Worker"
  ;; Q: Is this even worth using a multi-method?
  ;; It's very tempting to use a 1-way Pedestal event chain.
  ;; It would be a lot more tempting if Pedestal supported
  ;; clojurescript.
  (fn [this action world-key worker event data]
    action))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(declare send-to-worker!)

(s/fdef event-forwarder
  :args (s/cat :clock ::lamport/clock
               :worker ::web-worker
               :control-id keyword?
               :tag keyword?)
  :ret (s/fspec :args (s/cat :event ::dom-event)
                ;; Event handler called for side-effects
                :ret any?))
(defn event-forwarder
  "Sanitize event and post it to Worker"
  ;; For an alternate, probably better, approach, see
  ;; https://threejsfundamentals.org/threejs/lessons/threejs-offscreencanvas.html
  [clock worker ctrl-id tag]
  ;; Q: Is it worth keeping these around long-term?
  ;; Or better to just create/discard them on the fly as needed?
  (fn [event]
    ;; TODO: Window manager needs to set up something so events
    ;; don't go to unfocused windows.
    ;; Those really need some kind of extra styling to show an
    ;; inactive overlay. The click (or mouse-over...whichever
    ;; matches the end-user's preferences) should transfer
    ;; focus.
    (.info js/console "Posting" tag "to web worker for" ctrl-id)
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
      (send-to-worker! worker :frereth/event [tag ctrl-id clone]))))

(defn on-*-replace
  [clock worker ctrl-id acc [k v]]
  (let [s (name k)
        prefix (subs s 0 3)]
    (assoc acc k
           (if (= "on-" prefix)
             (event-forwarder clock worker ctrl-id v)
             v))))

(defn sanitize-scripts
  "Convert scripting events to messages that get posted back to worker"
  [clock
   worker
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
                           (partial on-*-replace clock worker ctrl-id)
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
                       (sanitize-scripts clock worker content)
                       content))
                   body))))))

(s/fdef send-to-worker!
  :args (s/cat :clock ::lamport/clock
               :worker ::web-worker
               :action :frereth/action
               :payload :frereth/body))
(defn send-to-worker!
  [clock worker action payload]
  (.postMessage worker (serial/serialize
                        {:frereth/action action
                         :frereth/body payload
                         ::lamport/clock @clock})))

(defmethod handle-worker-message :default
  [{:keys [:web-socket/wrapper]
    :as this}
   action world-key worker event raw-message]
  (.error js/console "Unhandled action:" action
          "\nfrom world:" world-key))

(defmethod handle-worker-message :frereth/forked
  [{:keys [::web-socket/wrapper
           ::session/manager
           ::workers-need-dom-animation?]
    :as this}
   action world-key worker event
   {:keys [:frereth/needs-dom-animation?]
    :as raw-message}]
  (when needs-dom-animation?
    (reset! workers-need-dom-animation? needs-dom-animation?))
  (let [worlds (session/get-worlds manager)
        {:keys [::world/cookie]
         :as world-state} (world/get-world worlds
                                           world-key)]
    (if cookie
      (let [decorated (assoc raw-message
                             :frereth/cookie cookie
                             :frereth/world-key world-key)
            {:keys [::web-socket/socket]} wrapper]
        (web-socket/send-message! this
                                  world-key
                                  {:path-info "/api/v1/forked"
                                   :request-method :put
                                   ;; Anyway, that's premature optimization.
                                   ;; Worry about that detail later.
                                   ;; Even though this is the boundary area where
                                   ;; it's probably the most important.
                                   ;; (Well, not here. But in general)
                                   :body decorated})
        (session/do-mark-forked manager world-key worker))
      (.error js/console "Missing ::cookie among" world-state
              "\namong" (keys worlds)
              "\nin" worlds
              "\nfrom" this))))

;; Defer this to concrete "Window Manager" implementations.
;; It seems like it would be handy to have this call a
;; PureVirtualMethod exception to force concrete instances
;; to override.
;; Actually, it gets trickier than that.
;; The Window Manager shouldn't do the rendering. That's
;; the responsibility of the various...whatevers. Some
;; Worlds may be just fine as Reagent Components. Others
;; will just draw raw OpenGL.
;; Combining those is its own kettle of fish.
;; The Window Manager should handle things like compositing
;; and event passing.
#_(defmethod handle-worker-message :frereth/render
  [this action world-key worker event raw-message]
    (let [dom (sanitize-scripts clock
                                worker
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
  ;; I'd really like to feed this through a pedestal interceptor chain.
  ;; Since that's currently server-side only, I'll probably have to
  ;; bring bidi back into the mix.
  ;; Then again, there aren't really enough variations to justify
  ;; that.
  (let [raw-data (.-data event)
        {:keys [:frereth/action]
         :as data} (serial/deserialize raw-data)]
    (.log js/console "Message from" worker ":" action "in" data)
    (handle-worker-message this action world-key worker event data)))

(s/fdef fork-world-worker
  ;; Q: What was :this?
  ;; A: It's defined in session-socket.
  ;; Absolutely cannot use it here.
  ;; However, do need the semantics behind it.
  ;; TODO: Start back here
  :args (s/or :parameterized (s/cat :this ::manager
                                    :world-key ::jwk
                                    ;; FIXME: at least set up a regex
                                    :url string?
                                    :params map?)
              :basic (s/cat :this ::manager
                            :world-key ::jwk
                            :url string?))
  :ret ::web-worker)
(defn fork-world-worker
  ([this world-key base-url params]
   (let [url (if (empty? params)
               base-url
               (do
                 (.error js/console "Can't build a URL this way")
                 ;; Q: Building url this way can't possibly be right, can it?!
                 ;; A: Well...this is what I did in log-viewer
                 ;; But that was starting from cemerick.url.
                 ;; So no.
                 (str (assoc base-url :query params))))
         _ (.info js/console "Trying to open a web worker at" url)
         ;; Q: Can I use window.Worker. instead of `new`?
         worker (js/window.Worker.
                 url
                 ;; Currently redundant:
                 ;; in Chrome, at least, module scripts are not
                 ;; supported on DedicatedWorker
                 #js{"type" "classic"})]
     ;; TODO: At least in theory, we shoulld be able to to
     ;; set the ::workers-need-dom-animation value the first
     ;; time this gets called.
     ;; Which, really, should be to create the Login/Attract
     ;; Screen.
     ;; Which gets weird with auth and anonymity: what if
     ;; different end-users choose different Window
     ;; Managers?
     (.debug js/console "(.-requestAnimationFrame worker)"
             (.-requestAnimationFrame worker)
             "among"
             worker)
     (set! (.-onmessage worker)
           (partial on-worker-message this world-key worker))
     (set! (.-onerror worker)
           (fn [problem]
             (.error js/console "FIXME: Need to handle\n"
                     problem
                     "\nfrom World\n"
                     world-key)))
     worker))
  ([this world-key base-url]
   (fork-world-worker this world-key base-url {})))

(s/fdef fork-authenticated-worker!
  :args (s/cat :crypto ::subtle-crypto
               :this ::connection
               :session-id :frereth/session-id
               :key-pair ::internal-key-pair
               ;; Actually, this is anything that transit can serialize.
               ;; Well, any sort of key
               :world-key ::jwk
               :cookie ::cookie)
  :ret (s/nilable ::web-worker))
(defn fork-authenticated-worker!
  "Begin promise chain that leads to an authenticated, ::active World
  connected over a web socket"
  [crypto
   {:keys [::session/manager
           ::web-socket/wrapper]
    :as this}
   session-id
   {:keys [::secret]}
   world-key
   cookie]
  (when (.-Worker js/window)
    ;; <comment_rot from="2019-FEB">
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
    ;; Web Workers are designed to be relatively heavy-
    ;; weight.
    ;; Q: Is it worth spawning a new one for each
    ;; world (which are also supposed to be pretty hefty)?
    ;; </comment_rot>
    ;; (This answers the direct questions in that rotted
    ;; comment)
    ;; A: Want 1 web Worker per world. That provides the hefty
    ;; isolation guarantees that frereth requires (think i386
    ;; protected mode)
    ;; For the bigger-picture question, I think I have the
    ;; extra indirection layer I want, sort-of.
    ;; I think that putting
    ;; a) login screen into 1 web worker
    ;; b) "shell" into a second (window manager) that gets
    ;; activated after login
    ;; c) "real" apps that interact w/ "window manager" (this
    ;; part TBD)
    ;; and
    ;; d) top-level generic renderer that displays either
    ;;    1. login
    ;;    or
    ;;    2. window-manager + composited children
    ;; is just about what I want.
    ;; This current app is a step in that direction, but still
    ;; missing a lot of ingredients.
    (let [initiate {:frereth/cookie cookie
                    :frereth/session-id session-id
                    :frereth/world-key world-key}
          packet-string (serial/serialize initiate)
          packet-bytes (serial/str->array packet-string)
          _ (.log js/console "Signing" packet-bytes)
          signature-promise (.sign crypto
                                   ;; Q: Does ECDSA make any sense
                                   ;; at all?
                                   #js {:name "ECDSA"
                                        :hash #js{:name "SHA-256"}}
                                   secret
                                   packet-bytes)]
      ;; TODO: Look at the way promesa handles this kind of event chain.
      ;; Q: Is it really any cleaner?
      (.then signature-promise
             (fn [signature]
               (let [base-url (::web-socket/base-url wrapper)
                     sock (::web-socket/socket wrapper)
                     path-to-shell (::session/path-to-fork-shell manager)
                     url #_(url/url base-url path-to-shell) (str base-url "/" path-to-shell)
                     params {:frereth/initiate packet-string
                             ;; Web server uses the cookie to verify that
                             ;; the request comes from the correct World.
                             ;; Q: Is that comment about the cookie from the
                             ;; `initiate` packet or the actual HTTP cookie?
                             ;; (my guess is the former)
                             :frereth/signature (-> signature
                                                    serial/array-buffer->string
                                                    serial/serialize)}]
                 (.log js/console  "World signature prepared starting from\n"
                       wrapper
                       "\nConstructed Worker fork URL based on\n"
                       base-url
                       "\nadded" path-to-shell
                       "\nsigned with secret-key" secret)
                 (fork-world-worker this
                                    world-key
                                    url
                                    params)))))))

(s/fdef do-build-actual-worker
  :args (s/cat :this ::manager
               :ch ::async/chan
               :spawner ::work-spawner
               :raw-key-pair ::key-pair
               :full-pk ::public-key)
  :ret ::async/chan)
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
            (.log js/console "Received signal to fork worker:" response
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
                           (.warn js/console "Q: Would this be a better place to call session/do-mark-forked ?")))
                  (.warn js/console "Spawning shell failed"))
                (catch :default ex
                  (.error js/console ex
                          "\nUnhandler error spawnin worker")))
              (catch :default ex
                (.error js/console ex
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
  (.log js/console "Set up pending World. Notify about pending fork.")
  ;; Want this part to happen asap to minimize the length of time
  ;; we have to spend waiting on network hops
  (web-socket/send-message! this full-pk {:path-info "/api/v1/forking"
                                          :request-method :post
                                          :params {:frereth/command 'login
                                                   :frereth/world-key full-pk}})
  (.log js/console "Setting up worker 'fork' in worlds inside the manager in"
               this "\namong" (keys this))
  ;; Q: Should this next section wait on the forking-ACK?
  ;; (It's probably worth mentioning in that regard that, originally,
  ;; this got called before we called send-message!)
  (let [worlds (session/get-worlds manager)
        ch (async/chan)]
    (.log js/console "cljs JWK:" full-pk
          "\nworlds: " worlds)
    (session/add-pending-world! manager full-pk ch {})
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
               :session-id :frereth/session-id
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
  :args (s/cat :this ::manager
               :session-id :frereth/session-id)
  :ret any?)
(defn fork-shell!
  [this session-id]
  (.log js/console "Setting up shell fork request")
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
                                   (.log js/console "Exporting the signing key we just generated")
                                   (reset! key-pair-atom key-pair)
                                   (export-public-key!
                                    this
                                    session-id
                                    crypto
                                    key-pair)))]
    (.then export-pk-promise (fn [exported]
                               (.log js/console "Building the worker to spawn")
                               (let [key-pair @key-pair-atom
                                     raw-public (.-publicKey key-pair)
                                     raw-secret (.-privateKey key-pair)
                                     clj-exported (js->clj exported)]
                                 (build-worker-from-exported-key
                                  this
                                  (partial fork-authenticated-worker!
                                           crypto
                                           this
                                           session-id
                                           {::public raw-public
                                            ::secret raw-secret}
                                           clj-exported)
                                  @key-pair-atom
                                  clj-exported))))))

(defmethod ig/init-key ::manager
  [_ {:keys [::lamport/clock]
      session-manager ::session/manager
      :as this}]
  {:pre [clock session-manager]}
  ;; FIXME: session-id should come from the server as a header.
  ;; Or possibly in (.-cookie js/document)
  (let [session-id (random-uuid)]
    (assoc this
           ::workers (atom {})
           ::workers-need-dom-animation? (atom false))))

(defmethod ig/halt-key! ::manager
  [_ {:keys [::lamport/clock
             ::workers]}]
  (doseq [worker workers]
    (lamport/do-tick clock)
    (send-to-worker! clock worker :frereth/halt {})))
