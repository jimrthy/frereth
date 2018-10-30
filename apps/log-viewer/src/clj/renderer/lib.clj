(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require [cognitect.transit :as transit]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [frereth-cp.shared.crypto :as crypto]
            [manifold
             [deferred :as dfrd]
             [stream :as strm]])
  (:import clojure.lang.ExceptionInfo
           [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]
           java.util.Base64))

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

(def pending-sessions
  "This really should be an atom. Or part of a bigger Universe-state atom"
  ;; For now, just hard-code some arbitrary random key as a baby-step
  (atom #{test-key}))

(def active-sessions
  "Connections to actual browser sessions"
  (atom {}))

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

(s/fdef serialize
  :args (s/cat :world-id any?
               :value any?)
  :ret bytes?)
(defn serialize
  [world-id value]
  (let [unwrapped-envelope {:frereth/world-id world-id
                            :frereth/body value}
        envelope (ByteArrayOutputStream. 4096)  ; Q: Useful size?
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

(defmethod dispatch! :default
  [session-id
   body]
  (println "Unhandled action:" body))

(defmethod dispatch! :frereth/forked
  [session-id
   {:keys [:frereth/pid]}]
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
  (throw (RuntimeException. "Not Implemented")))

(defmethod dispatch! :frereth/forking
  [session-id
   {:keys [:frereth/command
           :frereth/pid]}]
  (if (and command pid)
    (let [session (get @active-sessions session-id)]
      (if-not (contains? session pid)
        (do
          ;; This is dangerous and easily exploited.
          ;; Really do need something like this to tell the client the URL for loading.
          ;; And update the routing table to be able to serve the javascript it's about
          ;; to request.
          ;; At the same time, it would be pretty trivial for a bunch of rogue clients to
          ;; overload a server this naive.
          ;; The fact that the client is authenticated helps with post-mortems, but
          ;; we should try to avoid those.
          ;; So need some sort of throttle on forks per second and/or pending
          ;; forks.

          ;; TODO: Put this data into an encrypted cookie. It could be
          ;; a real cookie (well, no it couldn't, unless we convert this
          ;; to an HTTP request) or a GET parameter.
          ;; Whichever.
          ;; Take a page from the CurveCP handshake. Use a
          ;; minute-cookie for encryption.
          ;; When the client notifies us that it has forked (and that
          ;; notification must be signed with the PID...it should
          ;; probably include the PID to match that part of the
          ;; protocol), we can decrypt the cookie and mark this World
          ;; active for the appropriate SESSION.
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
          (let [dscr {:frereth/pid pid
                      :frereth/state :frereth/pending
                      ;; We don't need to (require 'client.propagate) to be able
                      ;; to declare the dependency structure here.
                      ;; But we will need to do so once the browser side has
                      ;; ::forked and we need to start the System this describes.
                      ;; Of course, the system that gets created here depends
                      ;; on the :frereth/command parameter.
                      ;; Need to split this ns up to avoid the potential circular
                      ;; dependency.
                      :frereth/system-description {:client.propagate/monitor {}}}
                session-string (pr dscr)
                ;; Q: Will this need Unicode? UTF-8 seems safer
                session-bytes (.getBytes session-string "ASCII")
                encoded (.encode (Base64/getEncoder) session-bytes)
                ack (serialize pid {:frereth/action :frereth/ack-forking
                                    :frereth/cookie encoded})
                websock (::web-socket session)]
            (post-message! session-id ack)))
        (println "Error: trying to re-fork pid" pid)))
    (println (str "Missing either/both of '"
                  command
                  "' or/and '"
                  pid
                  "'"))))

(defn on-message
  "Deserialize and dispatch a raw message from browser"
  [session-id message-string]
  (println (str "Incoming message from "
                session-id
                ": "
                message-string))
  (if (get @active-sessions session-id)
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
             (keys @active-sessions)
             "\nPending:\n"
             @pending-sessions)))

(defn login-realized
  "Client has finished its authentication"
  [websocket wrapper]
  (if (and (not= ::drained wrapper)
           (not= ::timed-out wrapper))
    (let [envelope (deserialize wrapper)
          _ (println "Key pulled:" envelope)
          public-key (:frereth/body envelope)]
      (println "Trying to move\n" public-key "\nfrom\n"
               @pending-sessions)
      (if (@pending-sessions public-key)
        (do
          (println "Swapping")
          ;; FIXME: Also need to dissoc public-key from the pending set.
          ;; And send back the URL for the current user's shell.
          ;; Or maybe that should be standardized, with this public key
          ;; as a query parameter.
          (swap! active-sessions
                 assoc
                 public-key {::web-socket websocket})
          (println "Swapped:")
          (pprint websocket)
          ;; Set up the message handler
          (let [connection-closed
                (strm/consume (partial on-message
                                       public-key)
                              websocket)]
            ;; Cope with it closing
            (dfrd/on-realized connection-closed
                              (fn [succeeded]
                                (println (str "Socket closed cleanly"
                                          " for session "
                                          public-key
                                          ": "
                                          succeeded))
                                (swap! active-sessions
                                       dissoc
                                       public-key))
                              (fn [failure]
                                (println "Web socket failed for"
                                         "session"
                                         public-key
                                         ":"
                                         failure)
                                (swap! active-sessions
                                       dissoc
                                       public-key)))))
        (do
          (println "Not found")
          (throw (ex-info "Client trying to complete non-pending connection"
                          {::attempt public-key
                           ::pending @pending-sessions
                           ::connected @active-sessions})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef activate-session!
  :args (s/cat :connection (s/and strm/sink?
                                  strm/source?)))
(defn activate-session!
  "This smells suspiciously like a step in a communictions/security protocol.

  At the very least, we need a map of pending connections so we can mark this
  one complete"
  [websocket]
  (try
    ;; FIXME: Better handshake
    (println "Trying to pull the Renderer's key from new websocket")
    (pprint websocket)
    (let [first-response (strm/try-take! websocket ::drained 500 ::timed-out)]
      ;; TODO: Need the login exchange.
      ;; Should probably do that before opening the websocket.
      (dfrd/on-realized first-response
                        (partial login-realized websocket)
                        (fn [error]
                          (println "Failed pulling initial key:" error))))
    (catch ExceptionInfo ex
      ;; FIXME: Better error handling via tap>
      ;; As ironic as that seems
      (println "Renderer connection completion failed")
      (pprint ex)
      (.close websocket))))

(defn post-message!
  "Forward value to the associated World"
  [world-id value]
  (println "Trying to forward\n"
           value
           "\nto\n"
           world-id)
  (if-let [connection (-> active-sessions
                       deref
                       (get world-id))]
    (try
      (pprint connection)
      (let [envelope (serialize world-id value)]
        (let [success (strm/try-put! connection envelope 500 ::timed-out)]
          (dfrd/on-realized success
                            #(println value "forwarded:" %)
                            #(println value "Forwarding failed:" %))))
      (catch Exception ex
        (println "Message forwarding failed:" ex)))
    (do
      (println "No such world")
      (throw (ex-info "Trying to POST to unconnected World"
                      {::pending @pending-sessions
                       ::world-id world-id
                       ::connected @active-sessions})))))

(comment
  ;; cljs doesn't need to specify
  (transit/writer :json)
  )
