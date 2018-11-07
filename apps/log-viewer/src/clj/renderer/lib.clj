(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require [cognitect.transit :as transit]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [frereth-cp.shared.crypto :as crypto]
            [integrant.core :as ig]
            [manifold
             [deferred :as dfrd]
             [stream :as strm]]
            [clojure.java.io :as io])
  (:import clojure.lang.ExceptionInfo
           [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]
           java.util.Base64))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

(s/def :frereth/lamport integer?)


;; These are really anything that's
;; a) immutable (and thus suitable for use as a key in a map)
;; and b) directly serializable via transit
(s/def :frereth/pid any?)
(s/def :frereth/session-id any?)
(s/def :frereth/world-id :frereth/pid)

(s/def ::cookie (s/keys :req [:frereth/pid
                              :frereth/state
                              :frereth/system-description]))

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

(def test-key
  "Placeholder for crypto key to identify a connection.

  Because I have to start somewhere, and that isn't with
  key exchange algorithms.

  Mostly, I don't want to copy/paste this any more than I
  must."
  [-39 -55 106 103
   -31 117 120 57
   -102 12 -102 -36
   32 77 -66 -74
   97 29 9 16
   12 -79 -102 -96
   89 87 -73 116
   66 43 39 -61])

;; TODO: Just track the state with each session.
;; Combining them adds extra confusing nesting that just
;; leads to pain.
;; Might be interesting to track deactivated for historical
;; comparisons.
(def sessions
  (atom {::active {}
         ;; For now, just hard-code some arbitrary random key as a baby-step
         ::pending #{test-key}}))

(defmulti dispatch!
  "Send message to a World associated with session-id"
  (fn [session-id {:keys [:frereth/action]
                   :as body}]
    action))

;; The fact that I need to do this makes me more inclined to move the
;; dispatch! methods into a different ns.
(declare post-message!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(s/fdef do-wrap-message
  :args (s/cat :world-id :frereth/world-id
               :action :frereth/action
               :value :frereth/body)
  :ret ::message-envelope)
;; Need a lamport clock.
;; Honestly, this should be a Component in the System.
(let [lamport (atom 0)]
  (defn do-wrap-message
    [world-id action value]
    (swap! lamport inc)
    {:frereth/action action
     :frereth/body value
     :frereth/lamport @lamport
     :frereth/world-id world-id}))

(s/fdef serialize
  :args (s/cat :unwrapped-envelope ::message-envelope)
  :ret bytes?)
(defn serialize
  [unwrapped-envelope]
  (let [envelope (ByteArrayOutputStream. 4096)  ; Q: Useful size?
        writer (transit/writer envelope :json)]
    (transit/write writer unwrapped-envelope)
    (.toByteArray envelope)))

(comment
  (String. (serialize "12345" {:a 1 :b 2 :c 3}))
  )

(defn deserialize
  [message-string]
  (let [in (ByteArrayInputStream. (.getBytes message-string))
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn get-world-in-active-session
  [session-id world-key which]
  (get-in @sessions [::active session-id ::worlds which world-key]))

(defn get-active-world
  [session-id world-key]
  (get-world-in-active-session session-id world-key ::active))

(defn get-pending-world
  [session-id world-key]
  (get-world-in-active-session session-id world-key ::pending))

(defn activate-pending-world!
  [session-id world-key]
  (if-let [world (get-pending-world session-id world-key)]
    (swap! sessions
           (fn [browser-sessions]
             (-> browser-sessions
                 (assoc-in [::active ::worlds ::active world-key]
                           world)
                 (update-in [::active ::worlds ::pending]
                            dissoc
                            world-key))))))

(s/fdef decode-cookie
  :args (s/cat :cookie-bytes bytes?)
  :ret ::cookie)
(defn decode-cookie
  ;; All these variants fail during JSON parsing with the error message
  ;; "Unrecognized token 'B': was expecting ('true', 'false' or 'null')"
  [cookie-bytes #_cookie-string]
  (let [cookie-bytes (.decode (Base64/getDecoder) cookie-bytes)
        cookie-string (String. cookie-bytes)]
    (println "Trying to decode" cookie-string "a"
             (class cookie-string)
             "from"
             cookie-bytes "a" (class cookie-bytes))
    (deserialize cookie-string)))

(defmethod dispatch! :default
  [session-id
   body]
  (println "Unhandled action:" body))

(defmethod dispatch! :frereth/forked
  [session-id
   {world-key :frereth/pid
    :as params}]
  ;; Once the browser has its worker up and running, we need to start
  ;; interacting.
  ;; Actually, there's an entire lifecycle here.
  ;; It's probably worth contemplating the way React handles the entire
  ;; idea (though it may not fit at all).
  ;; Main point:
  ;; This needs to start up a new System of Component(s) that
  ;; :frereth/forking prepped.
  ;; In this specific case, the main piece of that is
  ;; :client.propagate/monitor
  (if-let [world (get-pending-world session-id world-key)]
    (do
      (activate-pending-world! session-id world-key)
      ;; This is where we truly need the cookie that describes which
      ;; start function to run.
      ;; TODO: Start back here.
      ;; I'm making this too complicated. Add an extra cycle at the
      ;; browser level. Web Worker starts and sends a query for its
      ;; Cookie. core posts that back. Then Web Worker can include
      ;; that in the body of ::forked.
      ;; It still leaves things a bit complicated here, but at least
      ;; I won't have to manually update whichever script actually
      ;; needs that info.
      (throw (ex-info "Need to trigger its start function"
                      {::unhandled ::forked
                       ::parameters params})))
    (throw (ex-info (str "Need to make world lookup simpler. Could not find")
                    {::active-session (-> sessions
                                          deref
                                          ::active
                                          (get session-id))
                     ::among params
                     ::world-key world-key
                     ::world-key-class (class world-key)}))))

(defn build-cookie
  [session-id
   world-key]
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
              ;; We don't need to (require 'client.propagate) to be able
              ;; to declare the dependency structure here.
              ;; But we will need to do so once the browser side has
              ;; ::forked and we need to start the System this describes.
              ;; Of course, the system that gets created here depends
              ;; on the :frereth/command parameter.
              ;; Need to split this ns up to avoid the potential circular
              ;; dependency.
              :frereth/system-description {:client.propagate/monitor {}}}
        world-system-bytes (serialize dscr)]
    ;; TODO: This needs to be encrypted by the current minute
    ;; key before we encode it.
    (.encode (Base64/getEncoder) world-system-bytes)))

(defmethod dispatch! :frereth/forking
  [session-id
   {:keys [:frereth/command
           :frereth/pid]}]
  (if (and command pid)
    (let [session (get-in @sessions [::active session-id])]
      (if session
        (let [worlds (::worlds session)]
          (if (and (not (contains? (::pending worlds) pid))
                   (not (contains? (::active worlds) pid)))
            (let [cookie (build-cookie session-id pid)]
              (post-message! session-id
                             pid
                             :frereth/ack-forking
                             {:frereth/cookie cookie}))
            (println "Error: trying to re-fork pid" pid)))))
    (println (str "Missing either/both of '"
                  command
                  "' or/and '"
                  pid
                  "'"))))

(defn on-message
  "Deserialize and dispatch a raw message from browser"
  [session-id message-string]
  ;; Q: Could I avoid a layer of indirection and just
  ;; have this body be the dispactch function for the dispatch!
  ;; multi?
  (println (str "Incoming message from "
                session-id
                ": "
                message-string))
  (if (get-in @sessions [::active session-id])
    (try
      (let [{:keys [:frereth/body]
             :as wrapper} (deserialize message-string)]
        ;; The actual point.
        ;; It's easy to miss this in the middle of the error handling.
        ;; Which is one reason this is worth keeping separate from the
        ;; dispatching code.
        (dispatch! session-id body))
      (catch Exception ex
        (println ex "trying to deserialize/dispatch" message-string)))
    ;; This consumes messages from the websocket associated
    ;; with public-key until that websocket closes.
    (println "This should be impossible\n"
             "No"
             session-id
             "\namong\n"
             (keys (::active @sessions))
             "\nPending:\n"
             (::pending @sessions))))

(defn login-realized
  "Client has finished its authentication"
  [websocket wrapper]
  (println ::login-realized "Received initial websocket message:" wrapper)
  (if (and (not= ::drained wrapper)
           (not= ::timed-out wrapper))
    (let [envelope (deserialize wrapper)
          _ (println ::login-realized "Key pulled:" envelope)
          session-key (:frereth/body envelope)]
      (println ::login-realized "Trying to move\n" session-key
               "a" (class session-key)
               "\nfrom\n"
               (::pending @sessions))
      (if (get (::pending @sessions) session-key)
        (do
          (println ::login-realized "Swapping")
          ;; FIXME: Also need to dissoc public-key from the pending set.
          ;; (current approach is strictly debug-only
          (swap! sessions
                 assoc-in
                 [::active session-key] {::web-socket websocket
                                         ::worlds {::active {}
                                                   ::pending #{}}})
          (println ::login-realized "Swapped:")
          (pprint websocket)
          ;; Set up the message handler
          (let [connection-closed
                (strm/consume (partial on-message
                                       session-key)
                              websocket)]
            ;; Cope with it closing
            (dfrd/on-realized connection-closed
                              (fn [succeeded]
                                (println (str ::login-realized
                                              " Socket closed cleanly"
                                              " for session "
                                              session-key
                                              ": "
                                              succeeded))
                                (swap! sessions
                                       #(update %
                                                ::active
                                                (dissoc
                                                 session-key))))
                              (fn [failure]
                                (println ::login-realized
                                         "Web socket failed for session"
                                         session-key
                                         ":"
                                         failure)
                                (swap! sessions
                                       (update
                                        ::active
                                        #(dissoc %
                                                 session-key)))))))
        (do
          (println ::login-realized "Not found")
          (throw (ex-info "Client trying to complete non-pending connection"
                          {::attempt session-key
                           ::sessions @sessions})))))
    (println ::login-realized
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
  :args (s/cat :connection (s/and strm/sink?
                                  strm/source?)))
(defn activate-session!
  "Browser is trying to initiate a new Session"
  [websocket]
  (try
    ;; FIXME: Better handshake
    (println ::activate-session!
             "Trying to pull the Renderer's key from new websocket")
    (let [first-response (strm/try-take! websocket ::drained 500 ::timed-out)]
      ;; TODO: Need the login exchange.
      ;; Should probably do that before opening the websocket, using SRP.
      (dfrd/on-realized first-response
                        (partial login-realized websocket)
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
  :args (s/cat :session-id :frereth/session-id
               :world-id :frereth/world-id
               :cookie-bytes bytes?)
  ;; Q: What makes sense for the real return value?
  :ret (s/nilable bytes?))
(defn get-code-for-world
  [actual-session-id world-id cookie-bytes]
  (if-let [session (get-in @sessions [::active actual-session-id])]
    (let [{:keys [:frereth/pid
                  :frereth/session-id
                  :frereth/system-description]
           :as cookie} (decode-cookie cookie-bytes)]
      (if (and pid session-id system-description)
        (if (verify-cookie actual-session-id world-id cookie)
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
                     cookie "!=" world-id
                     "\nor"
                     actual-session-id "!=" session-id)
            (throw (ex-info "Invalid Cookie: probable hacking attempt"
                            {:frereth/session-id actual-session-id
                             :frereth/world-id world-id
                             :frereth/cookie cookie}))))))
    (do
      (println "Missing session key\n"
               actual-session-id "a" (class actual-session-id)
               "\namong")
      (doseq [session-key (-> sessions deref ::active keys)]
        (println session-key "a" (class session-key)))
      (throw (ex-info "Trying to fork World for missing session"
                      {::sessions @sessions
                       ::session-id actual-session-id
                       ::world-id world-id
                       ::cookie-bytes cookie-bytes})))))

(s/fdef post-message!
  :args (s/cat :session-id :frereth/session-id
               :world-id :frereth/world-id
               :action :frereth/action
               :value :frereth/body)
  :ret any?)
(defn post-message!
  "Forward value to the associated World"
  [session-id world-id action value]
  (println (str "Trying to forward\n"
                value
                "\nto\n"
                world-id
                " in "
                session-id))
  (if-let [connection (-> sessions
                          deref
                          ::active
                          (get session-id))]
    (try
      (pprint connection)
      (let [wrapper (do-wrap-message world-id action value)
            envelope (serialize wrapper)
            success (strm/try-put! (::web-socket connection)
                                   envelope
                                   500
                                   ::timed-out)]
        (dfrd/on-realized success
                          #(println value "forwarded:" %)
                          #(println value "Forwarding failed:" %)))
      (catch Exception ex
        (println "Message forwarding failed:" ex)))
    (do
      (println "No such world")
      (throw (ex-info "Trying to POST to unconnected Session"
                      {::pending (::pending @sessions)
                       ::world-id session-id
                       ::connected (::active @sessions)})))))

(defn register-pending-world!
  "Browser has requested a World's Code. Time to take things seriously"
  [session-id world-key cookie]
  (swap! sessions
         update-in
         [::active session-id ::worlds ::pending]
         #(conj % world-key cookie)))

(comment
  ;; cljs doesn't need to specify
  (transit/writer :json)
  )
