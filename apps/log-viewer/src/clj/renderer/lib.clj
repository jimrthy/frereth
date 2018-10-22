(ns renderer.lib
  "Library functions specific for the web renderer"
  (:require [cognitect.transit :as transit]
            [manifold.stream :as strm])
  (:import [java.io ByteArrayInputStream
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
  ;; Since I'm only using 1 key as a starting point, plan on each of these being a sequence
  (atom {}))

(defn complete-renderer-connection!
  "This smells suspiciously like a step in a communictions/security protocol.

  At the very least, we need a map of pending connections so we can mark this
  one complete"
  [public-key connection]
  (if (some #{public-key} @pending-renderer-connections)
    ;; FIXME: Also need to dissoc public-key from the pending set
    (swap! renderer-connections
           (fn [conns]
             (update conns public-key
                     (fn [xs]
                       (conj xs connection)))))
    (throw (ex-info "Client trying to complete non-pending connection"
                    {::attempt public-key
                     ::pending @pending-renderer-connections
                     ::connected @renderer-connections}))))

(defn post-message
  "Forward value to the associated World"
  [world-id value]
  (if-let [connections (-> renderer-connections
                       deref
                       (get world-id))]
    (let [unwrapped-envelope {:frereth/world-id world-id
                              :frereth/value value}
          envelope (ByteArrayOutputStream. 4096)  ; Q: Useful size?
          writer (transit/writer envelope :json)]
      (transit/write writer unwrapped-envelope)
      ;; Q: What are the odds this will work?
      (strm/put! connections envelope))
    (throw (ex-info "Trying to POST to unconnected World"
                    {::pending pending-renderer-connections}))))
