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

(def exception-write-handler
  (transit/write-handler "exception"
                         (fn [ex]
                           (println ::exception-write-handler
                                    "WARNING: Trying to serialize a(n)"
                                    (class ex))
                           ;; Just as I was starting to think that a
                           ;; macro would make a lot of sense here,
                           ;; I feel like a lot more details are justified.
                           ;; Then again...they really should all be getting
                           ;; added already by the log handler.
                           (pr-str ex))))

(def manifold-stream-handler
  (transit/write-handler "manifold-stream"
                         (fn [s]
                           (println ::manifold-stream-handler
                                    "WARNING: Trying to serialize a(n)"
                                    (class s))
                           (pr-str s))))

(def nacl-key-pair-handler
  (transit/write-handler "nacl-key-pair"
                         (fn [pair]
                           (println ::nacl-key-pair-handler
                                    "WARNING: Trying to serialize a" (class pair))
                           ;; It's tempting to also log the private key.
                           ;; That would be a terrible mistake.
                           {::public-key (vec (.getPublicKey pair))})))

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
                                  clojure.lang.Agent atom-write-handler
                                  com.iwebpp.crypto.TweetNaclFast$Box$KeyPair nacl-key-pair-handler
                                  java.lang.Exception exception-write-handler
                                  manifold.stream.SplicedStream manifold-stream-handler}}
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
