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

(def pending-renderer-connections
  "This really should be an atom. Or part of a bigger Universe-state atom"
  ;; For now, just hard-code some arbitrary random key as a baby-step
  (atom #{test-key}))

(def renderer-connections
  "Connections to actual browser sessions"
  (atom {}))
(comment)

(s/fdef complete-renderer-connection!
  :args (s/cat :serialized-public-key string?
               :connection any?))
(defn complete-renderer-connection!
  "This smells suspiciously like a step in a communictions/security protocol.

  At the very least, we need a map of pending connections so we can mark this
  one complete"
  [connection]
  (try
    ;; FIXME: Better handshake
    (println "Trying to pull the Renderer's key from new websocket")
    (pprint connection)
    (let [first-response (strm/try-take! connection ::drained 500 ::timed-out)]
      (dfrd/on-realized first-response
                        (fn [serialized-key]
                          (if (and (not= ::drained serialized-key)
                                   (not= ::timed-out serialized-key))
                            (let [_ (println "Key pulled:" serialized-key)
                                  in (ByteArrayInputStream. (.getBytes serialized-key))
                                  reader (transit/reader in :json)
                                  public-key (transit/read reader)]
                              (println "Trying to move\n" public-key "\nfrom\n"
                                       @pending-renderer-connections)
                              (if (@pending-renderer-connections public-key)
                                (do
                                  (println "Swapping")
                                  ;; FIXME: Also need to dissoc public-key from the pending set.
                                  ;; And send back the URL for the current user's shell.
                                  ;; Or maybe that should be standardized, with this public key
                                  ;; as a query parameter.
                                  (swap! renderer-connections
                                         assoc
                                         public-key connection)
                                  (println "Swapped:")
                                  (pprint connection))
                                (do
                                  (println "Not found")
                                  (throw (ex-info "Client trying to complete non-pending connection"
                                                  {::attempt public-key
                                                   ::pending @pending-renderer-connections
                                                   ::connected @renderer-connections})))))))
                        (fn [error]
                          (println "Failed pulling initial key:" error))))
    (catch ExceptionInfo ex
      ;; FIXME: Better error handling via tap>
      ;; As ironic as that seems
      (println "Renderer connection completion failed")
      (pprint ex)
      (.close connection))))

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

(defn post-message
  "Forward value to the associated World"
  [world-id value]
  (println "Trying to forward\n"
           value
           "\nto\n"
           world-id)
  (if-let [connection (-> renderer-connections
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
                      {::pending @pending-renderer-connections
                       ::world-id world-id
                       ::connected @renderer-connections})))))

(comment
  ;; cljs doesn't need to specify
  (transit/writer :json)
  )
