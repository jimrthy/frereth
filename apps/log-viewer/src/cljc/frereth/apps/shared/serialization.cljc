(ns frereth.apps.shared.serialization
  (:require
   [clojure.spec.alpha :as s]
   [cognitect.transit :as transit]))

(s/def ::array-buffer #?(:clj bytes?
                         :cljs #(instance? js/ArrayBuffer %)))

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



(let [;; Q: msgpack ?
      ;; A: Not in clojurescript, yet.
      ;; At least, not "natively"
      writer (transit/writer :json)]

  (defn serialize
    "Encode using transit"
    [o]
    (transit/write writer o)))

(let [reader (transit/reader :json)]
  (defn deserialize
    [s]
    (console.log "Trying to read" s)
    (transit/read reader s)))
