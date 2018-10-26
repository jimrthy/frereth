(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require [cognitect.transit :as transit]
            [manifold.stream :as strm]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [manifold.deferred :as dfrd])
  (:import clojure.lang.ExceptionInfo
           [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

(defmethod dispatch! :frereth/fork
  [session-id {:keys [:frereth/pid]}]
  (let [session (get @active-sessions session-id)]
    (if-not (contains? session pid)
      (do
        (swap! active-sessions
               (fn [sessions]
                 ;; FIXME: Needs some sort of UUID to identify the app that's being spawned
                 (assoc-in sessions [session-id pid] {:frereth/state :frereth/pending}))))
      (println "Error: trying to re-fork pid" pid))))

;; TODO: Rename to serialize
(s/fdef wrap
  :args (s/cat :world-id any?
               :value any?)
  :ret bytes?)
(defn wrap
  [world-id value]
  (let [unwrapped-envelope {:frereth/world-id world-id
                            :frereth/body value}
        envelope (ByteArrayOutputStream. 4096)  ; Q: Useful size?
        writer (transit/writer envelope :json)]
    (transit/write writer unwrapped-envelope)
    (.toByteArray envelope)))

(comment
  (String. (wrap "12345" {:a 1 :b 2 :c 3}))
  )

(defn deserialize
  [message-string]
  (let [in (ByteArrayInputStream. (.getBytes message-string))
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn on-message
  "Deserialize and dispatch a raw message from browser"
  [public-key message-string]
  ;; This almost doesn't seem worth having
  (if (get @active-sessions public-key)
    (let [body (deserialize message-string)]
      (dispatch! public-key body))
    ;; This consumes messages from the websocket associated
    ;; with public-key until that websocket closes.
    (println "This should be impossible\n"
             "No"
             public-key
             "\namong\n"
             (keys @active-sessions)
             "\nPending:\n"
             @pending-sessions)))

(defn login-realized
  "Client has finished its authentication"
  [websocket serialized-key]
  (if (and (not= ::drained serialized-key)
           (not= ::timed-out serialized-key))
    (let [_ (println "Key pulled:" serialized-key)
          public-key (deserialize serialized-key)]
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
                                (println "Socket closed cleanly for"
                                         "session"
                                         public-key
                                         ":"
                                         succeeded)
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

(defn post-message
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
      (let [envelope (wrap world-id value)]
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
