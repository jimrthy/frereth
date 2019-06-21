(ns frereth.apps.shared.specs
  "These are really  more general"
  (:require  [clojure.core.async :as async]
             [clojure.core.async.impl.protocols :as async-protocols]
             [clojure.spec.alpha :as s]))

(s/def ::async/chan #(satisfies? async-protocols/Channel %))

;; It's tempting to make this a limited set.
;; But it's not like specifying that here would
;; make runtime callers any more reliable.
;; That really gets into things like runtime
;; message validation.
;; Which, honestly, should be pretty strict and
;; happen ASAP on both sides.
(s/def :frereth/action keyword?)

;; Called to disconnect renderer from a running world
(s/def :frereth/disconnect! (s/fspec :args nil :ret any?))

;; Not quite `any?`.
;; But anything that can be serialized to transit.
;; TODO: Find a spec for this
(s/def :frereth/message any?)

(s/def :frereth/message-sender!
  (s/fspec :args (s/cat :message :frereth/message)
           :ret any?))

(s/def #?(:clj :frereth/renderer->client
          :cljs :frereth/browser->worker) (s/keys :req [:frereth/disconnect!
                                                        :frereth/message-sender!]))

;; These are really anything that's
;; a) immutable (and thus suitable for use as a key in a map)
;; and b) directly serializable via transit
(s/def :frereth/pid any?)

(s/def ::time-in-state inst?)

#?(:cljs (s/def :frereth/worker #(instance? js/Worker %)))

(s/def :frereth/world-stop-signal (s/or :symbol symbol?
                                        :uuid uuid?
                                        :int integer?))
(s/def :frereth/world-connector
  (s/fspec :args (s/cat :world-stop-signal :frereth/world-stop-signal
                        :send-message! :frereth/message-sender!)
           :ret #?(:clj :frereth/renderer->client
                   :cljs :frereth/browser->worker)))
