(ns frereth.apps.shared.serialization
  (:require
   [clojure.core.async.impl.protocols :as async-protocols]
   [clojure.spec.alpha :as s]
   [cognitect.transit :as transit])
  #?(:clj (:import [java.io
                    ByteArrayInputStream
                    ByteArrayOutputStream])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def ::array-buffer #?(:clj bytes?
                         :cljs #(instance? js/ArrayBuffer %)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

;; Q: What would be a useful size?
(def ^:const buffer-out-size 4096)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Serialization handlers

(def async-chan-write-handler
  "This really shouldn't be needed"
  (transit/write-handler "async-chan"
                         (fn [o]
                           (println ::async-chan-write-handler
                                    "WARNING: trying to serialize a(n)"
                                    (type o))
                           (pr-str o))))

(def atom-write-handler
  (transit/write-handler "clojure-atom"
                         (fn [o]
                           (println ::atom-write-handler
                                    "WARNING: Trying to serialize a(n)"
                                    (type o))
                           ;; Q: What's the proper way to
                           ;; serialize this recursively?
                           (pr-str @o))))

(def exception-write-handler
  (transit/write-handler "exception"
                         (fn [ex]
                           (println ::exception-write-handler
                                    "WARNING: Trying to serialize a(n)"
                                    (type ex))
                           ;; Just as I was starting to think that a
                           ;; macro would make a lot of sense here,
                           ;; I feel like a lot more details are justified.
                           ;; Then again...they really should all be getting
                           ;; added already by the log handler.
                           (pr-str ex))))

;;; Technically, it might make sense to try to make this work
;;; on the browser side.
;;; Since there *is* a cljs version of manifold that I've
;;; considered using.
#?(:clj
   (def manifold-stream-handler
     (transit/write-handler "manifold-stream"
                            (fn [s]
                              (println ::manifold-stream-handler
                                       "WARNING: Trying to serialize a(n)"
                                       (type s))
                              (pr-str s)))))

;;; It's also very tempting to make this actually work, so
;;; I can pass public keys back and forth efficiently.
#?(:clj
   (def nacl-key-pair-handler
     (transit/write-handler "nacl-key-pair"
                            (fn [pair]
                              (println ::nacl-key-pair-handler
                                       "WARNING: Trying to serialize a" (type pair))
                              ;; It's tempting to also log the private key.
                              ;; That would be a terrible mistake.
                              {::public-key (vec (.getPublicKey pair))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;; Q: Isn't this used publicly?
(s/fdef str->array
  :args (s/cat :s string?)
  :ret ::array-buffer)
(defn str->array
  "Convert a string to a js/ArrayBuffer"
  [s]
  #?(:clj (.getBytes s)
     ;; Translated from https://gist.github.com/andreburgaud/6f73fd2d690b629346b8
     ;; There's an inverse function there named arrayBufferToString which
     ;; is worth contrasting with array-buffer->string in terms of
     ;; performance.
     ;; Javascript:
     ;; String.fromCharCode.apply(null, new Uint16Array(buf));
     :cljs (let [buf (js/ArrayBuffer. (* 2 (count s)))
                 buf-view (js/Uint16Array. buf)]
             (dorun (map-indexed (fn [idx ch]
                                   (aset buf-view idx (.charCodeAt ch)))
                                 s))
             buf)))

(s/fdef array-buffer->string
  :args (s/cat :bs ::array-buffer)
  :ret string?)
(defn array-buffer->string
  "Convert a js/ArrayBuffer to a string"
  [bs]
  #?(:cljs
     (let [data-view (js/DataView. bs)
           ;; Q: What encoding is appropriate here?
           ;; (apparently javascript strings are utf-16)
           decoder (js/TextDecoder. "utf-8")]
       (.decode decoder data-view))
     :clj (String. bs)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef serialize
           ;; body isn't *really* "any?" It has to be something that's
           ;; directly serializable via transit.
           :args (s/cat :body any?)
           :ret ::array-buffer)

#?(:clj
   (defn serialize
     [body]
     (try
       (let [result (ByteArrayOutputStream. buffer-out-size)
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

   :cljs (let [;; Q: msgpack ?
               ;; A: Not in clojurescript, yet.
               ;; At least, not "natively"
               writer (transit/writer :json)]

           (defn serialize
             "Encode using transit"
             [o]
             (transit/write writer o))))

(s/fdef deserialize
  :args (s/cat :message-string string?)
  :ret ::array-buffer)
#?(:clj (defn deserialize
          [message-string]
          (let [message-bytes (.getBytes message-string)
                in (ByteArrayInputStream. message-bytes)
                reader (transit/reader in :json)]
            (transit/read reader)))

   :cljs (let [reader (transit/reader :json)]
           (defn deserialize
             [s]
             (.log js/console "Trying to read" s)
             (transit/read reader s))))

(comment
  (String. (serialize "12345" {:a 1 :b 2 :c 3}))
  #?(:clj (try
            (let [result (ByteArrayOutputStream. 4096)
                  writer (transit/writer result :json)]
              (transit/write writer {::ch (async/chan)})
              (.toByteArray result))
            (catch RuntimeException ex
              (println "Caught" ex)))))
