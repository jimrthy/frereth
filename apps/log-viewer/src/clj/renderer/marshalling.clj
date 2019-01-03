(ns renderer.marshalling
  ;; FIXME: Convert this into a .cljc file
  (:require [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [cognitect.transit :as transit])
  (:import [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]))

(def async-chan-write-handler
  "This really shouldn't be needed"
  (transit/write-handler "async-chan"
                         (fn [o]
                           (println ::async-chan-write-handler
                                    "WARNING: trying to serialize a(n)"
                                    (class o))
                           (pr-str o))))

(def atom-write-handler
  (transit/write-handler "clojure-atom"
                         (fn [o]
                           (println ::atom-write-handler
                                    "WARNING: Trying to serialize a(n)"
                                    (class o))
                           ;; Q: What's the proper way to
                           ;; serialize this recursively?
                           (pr-str @o))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef deserialize
  :args (s/cat :message-string string?)
  :ret bytes?)
(defn deserialize
  [message-string]
  (let [message-bytes (.getBytes message-string)
        in (ByteArrayInputStream. message-bytes)
        reader (transit/reader in :json)]
    (transit/read reader)))

(s/fdef serialize
  ;; body isn't *really* anything. It has to be something that's
  ;; directly serializable via transit.
  :args (s/cat :body any?)
  :ret bytes?)
(defn serialize
  [body]
  ;; Q: Useful size?
  (try
    (let [result (ByteArrayOutputStream. 4096)
          handler-map {:handlers {async-protocols/ReadPort async-chan-write-handler
                                  clojure.core.async.impl.channels.ManyToManyChannel async-chan-write-handler
                                  clojure.lang.Atom atom-write-handler
                                  clojure.lang.Agent atom-write-handler}}
          writer (transit/writer result
                                 :json
                                 handler-map)]
      (transit/write writer body)
      (.toByteArray result))))

(comment
  (String. (serialize "12345" {:a 1 :b 2 :c 3}))
  (try
    (let [result (ByteArrayOutputStream. 4096)
          writer (transit/writer result :json)]
      (transit/write writer {::ch (async/chan)})
      (.toByteArray result))
    (catch RuntimeException ex
      (println "Caught" ex)))
  )
