(ns frereth.apps.shared.worker
  "Handle the various web workers"
  (:require
   [cemerick.url :as url]
   [cljs.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.log-viewer.frontend.session :as session]
   [frereth.apps.log-viewer.frontend.socket :as web-socket]
   [frereth.apps.shared.serialization :as serial]
   ;; Start by at least partially supporting this, since it's
   ;; so popular
   [reagent.core :as r]
   [shared.lamport :as lamport]
   [shared.world :as world]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; This is actually an instance of js/WebWorker
(s/def ::worker any?)

(s/def ::worker-map (s/map-of :frereth/world-id ::worker))

(s/def ::workers (s/and #(instance? Atom %)
                        #(s/valid? ::worker-map (deref %))))

(s/def ::manager (s/keys :req [::lamport/clock
                               ::session/manager
                               ::web-socket/wrapper
                               ::workers]))

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
  :args (s/cat :socket any?
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

(defn on-worker-message
  "Cope with message from Worker"
  [{:keys [::web-socket/wrapper
           :frereth/worlds]
    :as this}
   world-key worker event]
  (let [data (.-data event)
        {:keys [::web-socket/socket]} wrapper]
    (try
      (let [{:keys [:frereth/action]
             :as raw-message} (serial/deserialize data)]
        (console.log "Message from" worker ":" action)
        (condp = action
          :frereth/render
          (let [dom (sanitize-scripts worker
                                      (:frereth/body raw-message))]
            (r/render-component [(constantly dom)]
                                (js/document.getElementById "app")))
          :frereth/forked
          (let [{:keys [::cookie]
                 :as world-state} (world/get-world worlds
                                                   world-key)
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
           ::web-socket/wrapper]
    :as this}
   session-id
   {:keys [::secret]}
   world-key
   cookie]
  (when window.Worker
    (let [sock (::web-socket/socket wrapper)
          base-url (::web-socket/base-url sock)]
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

(s/fdef build-worker-from-exported-key
  :args (s/cat :socket ::web-socket
               :spawner ::work-spawner
               :raw-key-pair ::key-pair)
  ;; Returns a core.async channel...but that seems like an
  ;; implementation detail
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef fork-shell!
  :args (s/cat :this ::manager)
  :ret any?)
(defn fork-shell!
  [{:as this}
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

(s/fdef manager
  :args (s/cat :clock ::lamport/clock
               :session-manager ::session/manager
               :web-sock-wrapper ::web-socket/wrapper)
  :ret ::manager)
(defn manager
  [clock session-manager web-sock-wrapper]
  {::lamport/clock clock
   ::session/manager session-manager
   ::web-socket/wrapper web-sock-wrapper
   ::workers (atom {})})
