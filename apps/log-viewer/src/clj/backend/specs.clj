(ns backend.specs
  (:require
   [clojure.core.async :as async]
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   ;; Initially, at least, this is for ::async/chan
   [frereth.apps.shared.specs :as specs]
   [frereth.weald.specs :as weald]
   [io.pedestal.interceptor.chain :as interceptor-chain]
   [renderer.sessions :as sessions])
  (:import clojure.lang.ExceptionInfo))

;; Q: Would it make more sense to move these into a Pedestal-specific ns?
;; There's some definite overlap with the backend.web.service ns

;; FIXME: Specify which keys are expected for Request and Response
(s/def ::request map?)
(s/def ::response map?)
(s/def ::interceptor-chain/error #(instance? ExceptionInfo %))

(s/def ::context (s/keys :req-un [::request]
                         :opt-un [::interceptor-chain/error
                                  ::response]))

(s/def ::possibly-deferred-context (s/or :context ::context
                                         :deferred ::async/chan))

(s/def ::enter (s/fspec :args (s/cat :context ::context)
                        :ret ::possibly-deferred-context))

(s/def ::leave (s/fspec :args (s/cat :context ::context)
                        :ret ::possibly-deferred-context))

(s/def ::error (s/fspec :args (s/cat :context ::context
                                     :exception ::interceptor-chain/error)
                        :ret ::context))

;; Q: What's actually allowed here?
(s/def ::name keyword?)

;; Actually, this is a dict that's suitable for converting into an
;; interceptor.
(s/def ::interceptor (s/keys :opt-un [::enter ::error ::leave ::name]))

;;; I know I've written a spec for this at some point or other.
;;; FIXME: Track that down so I don't have to duplicate the effort.
(s/def ::ring-request map?)

;; TODO: Write this, assuming no one else already has.
;; It's a Pedestal route table
(s/def ::routes any?)
