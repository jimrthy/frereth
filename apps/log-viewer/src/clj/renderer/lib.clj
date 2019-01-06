(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require [client.registrar :as registrar]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [frereth.cp.shared.crypto :as crypto]
            ;; TODO: something interesting with this
            [integrant.core :as ig]
            [manifold
             [deferred :as dfrd]
             [stream :as strm]]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [renderer
             [marshalling :as marshall]
             [sessions :as sessions]]
            [shared
             [specs]
             [lamport :as lamport]])
  (:import clojure.lang.ExceptionInfo
           java.util.Base64))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; It's tempting to make this a limited set.
;; But it's not like specifying that here would
;; make runtime callers any more reliable.
;; That really gets into things like runtime
;; message validation.
;; Which, honestly, should be pretty strict and
;; happen ASAP on both sides.
(s/def :frereth/action keyword?)

(s/def :frereth/body any?)

(s/def ::cookie (s/keys :req [:frereth/pid
                              :frereth/state
                              :frereth/world-ctor]))

;; Note that this assumes messages go to a specific world.
;; Q: How valid is that assumption?
(s/def ::message-envelope (s/keys :req [:frereth/action
                                        :frereth/body
                                        :frereth/lamport
                                        :frereth/world-id]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(def minute-key
  ;; TODO: Need another System Component that rotates this once a
  ;; minute
  "Symmetric key used for encrypting short-term key pairs in a Cookie

  (using the CurveCP, not web, sense of the term)"
  (atom (crypto/random-key)))

(def previous-minute-key
  ;; TODO: Need another System Component that rotates this once a
  ;; minute
  "Old symmetric key used for encrypting short-term key pairs in Cookie

  (using the CurveCP, not web, sense of the term)"
  (atom (crypto/random-key)))

(comment (vec @minute-key)
         (vec @previous-minute-key)
         test-key
         )

(defmulti dispatch!
  "Send message to a World associated with session-id"
  (fn [session  ; ::sessions/sessions
       session-id {:keys [:frereth/action]
                   :as body}]
    action))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef do-wrap-message
  :args (s/or :with-body (s/cat :world-key :frereth/world-id
                                :action :frereth/action
                                :value :frereth/body)
              :sans-body (s/cat :world-key :frereth/world-id
                                :action :frereth/action))
  :ret ::message-envelope)
;; Honestly, this should be a Component in the System.
;; And it needs to interact with the clock from weald.
;; TODO: Switch to using a :shared.lamport/clock.
;; Interestingly enough, that makes it tempting to convert both
;; do-wrap-message and on-message! into a Protocol.
(let [lamport (atom 0)]
  (defn do-wrap-message
    ([world-key action]
     (println "Building a" action "message")
     (swap! lamport inc)
     {:frereth/action action
      :frereth/lamport @lamport
      :frereth/world-id world-key})
    ([world-key action value]
     (let [result
           (assoc (do-wrap-message world-key action)
                  :frereth/body value)]
       (println "Adding" value "to message")
       result))))

(s/fdef on-message!
  :args (s/cat :clock ::lamport/clock
               :session-id ::sessions/session-id
               :message-string string?)
  ;; Called for side-effects
  :ret any?)
(defn on-message!
  "Deserialize and dispatch a raw message from browser"
  [clock session-id message-string]
  ;; Q: Would it make sense to avoid a layer of indirection and just
  ;; have this body be the dispactch function for the dispatch!
  ;; multi?

  ;; FIXME: Set things up so sessions is another parameter to on-message
  ;; rather than this sort of global
  (if (sessions/get-active-session @sessions/sessions session-id)
    (try
      (let [{:keys [:frereth/body]
             remote-clock :frereth/lamport
             :as wrapper} (marshall/deserialize message-string)
            local-clock (lamport/do-tick clock remote-clock)]
        ;; The actual point.
        ;; It's easy to miss this in the middle of the error handling.
        ;; Which is one reason this is worth keeping separate from the
        ;; dispatching code.
        (swap! sessions/sessions dispatch! session-id body))
      (catch Exception ex
        (println ex "trying to deserialize/dispatch" message-string)))
    (do
      (lamport/do-tick clock)
      ;; This consumes messages from the websocket associated
      ;; with public-key until that websocket closes.
      (println "This should be impossible\n"
               "No"
               session-id
               "\namong\n"
               (keys (::active @sessions/sessions))
               "\nPending:\n"
               (::pending @sessions/sessions)
               "\nat" @clock))))

(defn deactivate-world!
  [session-id world-key]
  ;; This would be significantly easier if I just tracked the state in
  ;; the world rather than different parts of this tree.
  (swap! sessions/sessions
         #(update-in % [::active session-id ::worlds ::active]
                     dissoc
                     world-key)))

(s/fdef assoc-active-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               ;; Well, any immutable value.
               ;; Although, in practice, this will probably generally be
               ;; keyword?
               ;; Note that this is really a leftover from the initial
               ;; implementation that assumed the world state would be a
               ;; hashmap.
               ;; I've since come to the realization that that
               ;; assumption isn't really a valid starting point for
               ;; generic usefulness.
               ;; It probably covers 90-95% of use cases, but it isn't
               ;; universal.
               ;; Many end-users, for example, will want to use a Record
               ;; instead.
               ;; It seems likely that others will want to use a mutable
               ;; java Object.
               ;; So it probably doesn't make sense to take this any
               ;; further.
               ;; However:
               ;; It probably does make sense to write an update
               ;; function that lets callers supply a callable.
               ;; And, for that matter, this still makes sense for
               ;; worlds that are represented by hashmaps. Or that want
               ;; to just overwrite one of a Record's properties.
               ;; From that perspective, this should really allow the
               ;; capability to update multiple key/value pairs at once.
               ;; Furthermore:
               ;; This really isn't for the arbitrary world-state.
               ;; This is for the sake of associating things at this
               ;; abstraction layer. Such as a ::client.
               :key any?
               :value any?)
  :ret ::sessions)
(defn assoc-active-world
  "Add a key/value pair to an active world"
  [sessions session-id world-key k v]
  (let [key-chain [::active session-id ::worlds ::active world-key]]
    (assoc-in sessions
              (conj key-chain k)
              v)))

(s/fdef activate-pending-world
  :args (s/cat :sessions ::sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-key)
  :ret ::sessions)
(defn activate-pending-world
  [sessions session-id world-key]
  (when-let [world (sessions/get-pending-world sessions session-id world-key)]
    ;; TODO: Caller is responsible for wrapping this in a swap! call.
    (throw (RuntimeException. "sessions is no longer an atom"))
    (swap! sessions
           (fn [browser-sessions]
             ;; This is a relic from the time before I decided to
             ;; track the state inside each individual Session/World.
             (throw (RuntimeException. "No longer"))
             (-> browser-sessions
                 (assoc-in [::active ::worlds ::active world-key]
                           world)
                 (update-in [::active ::worlds ::pending]
                            dissoc
                            world-key))))))

(s/fdef build-cookie
  :args (s/cat :session-id ::session-id
               :world-key ::world-key
               :command :frereth/command)
  :ret bytes?)
(defn build-cookie
  [session-id
   world-key
   command]
  ;; This approach seems overly complex, but setting up more state
  ;; is dangerous and easily exploited.
  ;; The fact that the client is authenticated helps with post-mortems, but
  ;; we should try to avoid those.
  ;; We also need some sort of throttle on forks per second and/or pending
  ;; forks.

  ;; Take a page from the CurveCP handshake. Use a
  ;; minute-cookie for encryption.
  ;; When the client notifies us that it has forked, we can
  ;; decrypt the cookie and mark this World active for the
  ;; appropriate SESSION.
  ;; Q: Would it be worthwhile to add another layer to this?
  ;; Have the browser query for the worker code. We use this
  ;; cookie to inject another short-term cookie key into that
  ;; worker code.
  ;; Then the ::forked handler could verify them all.
  ;; It seems like we really have to do something along those
  ;; lines, since we cannot possibly trust the browser and the
  ;; HTTP request could come from anywhere.
  ;; A malicious client could still share the client's private
  ;; key and request a billion copies of the browser page.
  ;; Then again, that seems like a weakness in CurveCP also.
  (let [dscr {:frereth/pid world-key
              :frereth/session-id session-id
              :frereth/world-ctor command}
        world-system-bytes (marshall/serialize dscr)]
    ;; TODO: This needs to be encrypted by the current minute
    ;; key before we encode it.
    ;; Q: Is it worth keeping a copy of the encoder around persistently?
    (.encode (Base64/getEncoder) world-system-bytes)))

(s/fdef decode-cookie
  :args (s/cat :cookie-bytes bytes?)
  :ret ::cookie)
(defn decode-cookie
  [cookie-bytes]
  (let [cookie-bytes (.decode (Base64/getDecoder) cookie-bytes)
        cookie-string (String. cookie-bytes)]
    (println "Trying to decode" cookie-string "a"
             (class cookie-string)
             "from"
             cookie-bytes "a" (class cookie-bytes))
    (marshall/deserialize cookie-string)))

(s/fdef post-real-message!
  :args (s/cat :sessions ::sessions/sessions
               :sesion-id :frereth/session-id
               :world-key :frereth/world-key
               ;; This needs to be anything that we can cleanly
               ;; serialize
               :wrapper any?)
  :ret any?)
(defn post-real-message!
  "Forward marshalled value to the associated World"
  [sessions session-id world-key wrapper]
  (println (str "Trying to send "
                wrapper
                "\nto "
                world-key
                "\nin\n"
                session-id))
  (if-let [connection (-> sessions
                          ::active
                          (get session-id))]
    (try
      (let [envelope (marshall/serialize wrapper)]
        (try
          (let [success (strm/try-put! (::web-socket connection)
                                       envelope
                                       500
                                       ::timed-out)]
            (dfrd/on-realized success
                              #(println "forwarded:" %)
                              #(println "Forwarding failed:" %)))
          (catch Exception ex
            (println "Message forwarding failed:" ex))))
      (catch Exception ex
        (println "Serializing message failed:" ex)))
    (do
      (println "World not connected")
      (throw (ex-info "Trying to POST to unconnected Session"
                      {::pending (::pending @sessions)
                       ::world-id session-id
                       ::connected (::active @sessions)})))))

(s/fdef post-message!
  :args (s/or :with-value (s/cat :sessions ::sessions/sessions
                                 :session-id :frereth/session-id
                                 :world-key :frereth/world-id
                                 :action :frereth/action
                                 :value :frereth/body)
              :sans-value (s/cat :sessions ::sessions/sessions
                                 :session-id :frereth/session-id
                                 :world-key :frereth/world-id
                                 :action :frereth/action))
  :ret any?)
(defn post-message!
  "Marshalling wrapper around post-real-message!"
  ([sessions session-id world-key action value]
   (println ::post-message! " with value " value)
   (let [wrapper (do-wrap-message world-key action value)]
     (post-real-message! sessions session-id world-key wrapper)))
  ([sessions session-id world-key action]
   (println ::post-message! " without value")
   (let [wrapper (do-wrap-message world-key action)]
     (post-real-message! sessions session-id world-key wrapper))))

(defmethod dispatch! :default
  [sessions
   session-id
   body]
  (println "Unhandled action:" body))

(defmethod dispatch! :frereth/forked
  [sessions
   session-id
   {world-key :frereth/pid
    :keys [:frereth/cookie]
    :as params}]
  ;; Once the browser has its worker up and running, we need to start
  ;; interacting.
  ;; Actually, there's an entire lifecycle here.
  ;; It's probably worth contemplating the way React handles the entire
  ;; idea (though it may not fit at all).
  ;; Main point:
  ;; This needs to start up a new System of Component(s) that
  ;; :frereth/forking prepped.
  (if-let [world (sessions/get-pending-world sessions session-id world-key)]
    (let [{actual-pid :frereth/pid
           actual-session :frereth/session-id
           constructor-key :frereth/world-ctor
           :as decrypted} (decode-cookie cookie)]
      (if (and (= session-id actual-session)
               (= world-key actual-pid))
        (let [connector (registrar/lookup session-id constructor-key)
              world-stop-signal (gensym "frereth.stop.")
              client (connector world-stop-signal
                                (fn [raw-message]
                                  (if (not= raw-message world-stop-signal)
                                    (post-message! sessions
                                                   session-id
                                                   world-key
                                                   :frereth/forward
                                                   raw-message)
                                    (do
                                      (deactivate-world! session-id world-key)
                                      (post-message! sessions
                                                     session-id
                                                     world-key
                                                     :frereth/disconnect
                                                     true)))))]
          (post-message! sessions session-id world-key :frereth/ack-forked)
          (let [session-map (activate-pending-world sessions
                                                    session-id
                                                    world-key)]
            (assoc-active-world sessions
                                session-id
                                world-key
                                ::client client)))
        ;; This could be a misbehaving World.
        ;; Or it could be a nasty bug in the rendering code.
        ;; Well, it would have to be a nasty bug for a World to misbehave
        ;; this way.
        ;; At this point, we should probably assume that the browser is
        ;; completely compromised and take drastic measures.
        ;; Notifying the user and closing the websocket to end the session
        ;; might be a reasonable start.
        (let [error-message
              (str "session/world mismatch\n"
                   (cond
                     (not= session-id actual-session) (str session-id
                                                           ", a "
                                                           (class session-id)
                                                           " != "
                                                           actual-session
                                                           ", a "
                                                           (class actual-session))
                     :else (str world-key ", a " (class world-key)
                                " != "
                                actual-pid ", a " (class actual-pid)))
                   "\nQ: notify browser?")]
          (throw (ex-info error-message
                          {::actual decrypted
                           ::expected {:frereth/session session-id
                                       :frereth/world-key world-key}})))))
    (throw (ex-info (str "Need to make world lookup simpler. Could not find")
                    {::active-session (-> sessions/sessions
                                          deref
                                          ::active
                                          (get session-id))
                     ::among params
                     ::world-key world-key
                     ::world-key-class (class world-key)}))))

(defmethod dispatch! :frereth/forking
  [sessions
   session-id
   {:keys [:frereth/command
           :frereth/pid]}]
  (if (and command pid)
    (when-let [session (sessions/get-active-session sessions session-id)]
      (let [worlds (::worlds session)]
        (if (and worlds
                 (throw (RuntimeException. "World state is no longer split like this"))
                 (not (contains? (::pending worlds) pid))
                 (not (contains? (::active worlds) pid)))
          ;; TODO: Need to check with the Client registry
          ;; to verify that command is legal for the current
          ;; session.
          (let [cookie (build-cookie session-id pid command)]
            (post-message! sessions
                           session-id
                           pid
                           :frereth/ack-forking
                           {:frereth/cookie cookie}))
          (println "Error: trying to re-fork pid" pid))))
    (println (str "Missing either/both of '"
                  command
                  "' or/and '"
                  pid
                  "'"))))

(s/fdef login-realized!
  :args (s/cat :lamport ::lamport/clock
               :session-atom ::sessions/session-atom
               ;; FIXME: This deserves a named spec
               :websocket (s/and strm/sink?
                                 strm/source?)
               :wrapper string?)
  :ret any?)
(defn login-realized!
  "Client has finished its authentication"
  [lamport session-atom websocket wrapper]
  (println ::login-realized! "Received initial websocket message:" wrapper)
  (if (and (not= ::drained wrapper)
           (not= ::timed-out wrapper))
    (let [envelope (marshall/deserialize wrapper)
          _ (println ::login-realized! "Key pulled:" envelope)
          session-key (:frereth/body envelope)]
      (println ::login-realized! "Trying to move\n" session-key
               "a" (class session-key)
               "\nfrom\n"
               ;; FIXME: This isn't the way sessions handle state any longer
               (::pending @session-atom))
      (if (get (::pending @session-atom) session-key)
        (do
          (println ::login-realized! "Swapping")
          ;; FIXME: Also need to dissoc public-key from the pending set.
          ;; (current approach with a hard-coded key that just lives
          ;; there permanently is strictly debug-only)
          (swap! session-atom
                 assoc-in
                 [::active session-key] {::web-socket websocket
                                         ::worlds {::active {}
                                                   ::pending #{}}})
          (println ::login-realized! "Swapped:")
          (pprint websocket)
          ;; Set up the message handler
          (let [connection-closed
                (strm/consume (partial on-message!
                                       lamport
                                       session-key)
                              websocket)]
            ;; Cope with it closing
            (dfrd/on-realized connection-closed
                              (fn [succeeded]
                                (println (str ::login-realized!
                                              " Socket closed cleanly"
                                              " for session "
                                              session-key
                                              ": "
                                              succeeded))
                                (swap! session-atom
                                       (fn [current]
                                         (update current
                                                 ::active
                                                 #(dissoc %
                                                          session-key)))))
                              (fn [failure]
                                (println ::login-realized!
                                         "Web socket failed for session"
                                         session-key
                                         ":"
                                         failure)
                                (swap! session-atom
                                       (fn [current]
                                         (update current
                                                 ::active
                                                 #(dissoc %
                                                          session-key))))))))
        (do
          (println ::login-realized! "Not found")
          (throw (ex-info "Browser trying to complete non-pending connection"
                          {::attempt session-key
                           ::sessions @session-atom})))))
    (println ::login-realized!
             "Waiting for login completion failed:"
             wrapper)))

(s/fdef verify-cookie
  :args (s/cat :session-id :frereth/session-id
               :world-id :frereth/world-key)
  :ret boolean?)
(defn verify-cookie
  [actual-session-id
   world-id
   {:keys [:frereth/pid
           :frereth/session-id]
    :as cookie}]
  ;; TODO: Need to verify the cookie plus its signature
  (and (= pid world-id)
       (= session-id actual-session-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef activate-session!
  :args (s/cat :lamport-clock ::lamport/clock
               :session-atom ::sessions/session-atom
               :connection (s/and strm/sink?
                                  strm/source?)))
(defn activate-session!
  "Browser is trying to initiate a new Session"
  [lamport-clock session-atom websocket]
  (try
    ;; FIXME: Better handshake (need an authentication phase)
    (println ::activate-session!
             "Trying to pull the Renderer's key from new websocket")
    (let [first-response (strm/try-take! websocket ::drained 500 ::timed-out)]
      ;; TODO: Need the login exchange.
      ;; Do that before opening the websocket, using SRP.
      ;; That should add the Session's short-term key to the ::pending
      ;; session map.
      ;; In order to authenticate, it had to already contact its Server.
      ;; So it should also have its view of what's going on with this
      ;; Session.
      ;; Honestly, this is mostly a FSM manager for the initial handshake.
      ;; Though passing messages back and forth over the web socket later
      ;; should/will be a much bigger drain on system resources.
      ;; TODO: Check this FSM handshake with stack overflow's security
      ;; board.
      (dfrd/on-realized first-response
                        (partial login-realized! lamport-clock session-atom websocket)
                        (fn [error]
                          (println ::activate-session!
                                   "Failed pulling initial key:" error))))
    (catch ExceptionInfo ex
      ;; FIXME: Better error handling via tap>
      ;; As ironic as that seems
      (println ::activate-session!
               "Renderer connection completion failed")
      (pprint ex)
      (.close websocket))))

(s/fdef get-code-for-world
  :args (s/cat :sessions ::sessions/sessions
               :session-id :frereth/session-id
               :world-key :frereth/world-id
               :cookie-bytes bytes?)
  ;; Q: What makes sense for the real return value?
  :ret (s/nilable bytes?))
(defn get-code-for-world
  [sessions actual-session-id world-key cookie-bytes]
  (if-let [session (get-in sessions [::active actual-session-id])]
    (let [{:keys [:frereth/pid
                  :frereth/session-id
                  :frereth/world-ctor]
           :as cookie} (decode-cookie cookie-bytes)]
      (println ::get-code-for-world "Have a session. Decoded cookie")
      (if (and pid session-id world-ctor)
        (if (verify-cookie actual-session-id world-key cookie)
          (do
            (println "Cookie verified")
            (let [opener (if (.exists (io/file "dev-output/js"))
                           io/file
                           (fn [file-name]
                             (io/file (io/resource (str "js/" file-name)))))
                  raw (opener "dev-output/js/worker.js")]
              (when-not (.exists raw)
                (throw (ex-info "Missing worker file"
                                {::problem opener})))
              ;; I still need access to the actual worker .cljs so
              ;; I can inject the public key that must be part of the cookie.
              ;; This is really just the .js that loads up that .cljs.
              ;; Though, honestly, this needs to adjust all the calls to
              ;; require to place them under an API route that involves
              ;; both the session and world IDs.
              (println "Returning" raw "a" (class raw))
              raw))
          (do
            (println "Bad Initiate packet.\n"
                     cookie "!=" world-key
                     "\nor"
                     actual-session-id "!=" session-id)
            (throw (ex-info "Invalid Cookie: probable hacking attempt"
                            {:frereth/session-id actual-session-id
                             :frereth/world-id world-key
                             :frereth/cookie cookie}))))
        (do
          (println (str "Incoming cookie has issue with either '"
                        pid
                        "', '"
                        session-id
                        "', or '"
                        world-ctor
                        "'"))
          (throw (ex-info "Bad cookie"
                          cookie)))))
    (do
      (println "Missing session key\n"
               actual-session-id "a" (class actual-session-id)
               "\namong")
      (doseq [session-key (-> sessions ::active keys)]
        (println session-key "a" (class session-key)))
      (throw (ex-info "Trying to fork World for missing session"
                      {::sessions sessions
                       :frereth/session-id actual-session-id
                       :frereth/world-id world-key
                       ::cookie-bytes cookie-bytes})))))

(s/fdef register-pending-world!
  :args (s/cat :session-atom ::sessions/session-atom
               :session-id :frereth/session-id
               :world-key :frereth/world-key
               :cookie ::cookie))
(defn register-pending-world!
  "Browser has requested a World's Code. Time to take things seriously"
  [session-atom session-id world-key cookie]
  (swap! session-atom
         update-in
         [::active session-id ::worlds ::pending]
         #(conj % world-key cookie)))
