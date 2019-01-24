(ns frereth.apps.login-viewer.specs
  "These are really  more general"
  ;; i.e. they deserve to live somewhere more like
  ;; frereth.rendererer.specs
  (:require [clojure.spec.alpha :as s]))

;; Called to disconnect renderer from a running world
(s/def :frereth/disconnect! (s/fspec :args nil :ret any?))

;; Not quite `any?`.
;; But anything that can be serialized to transit.
;; TODO: Find a spec for this
(s/def :frereth/message any?)

(s/def :frereth/message-sender!
  (s/fspec :args (s/cat :message :frereth/message)
           :ret any?))

(s/def :frereth/renderer->client (s/keys :req [:frereth/disconnect!
                                               :frereth/message-sender!]))

(s/def :frereth/world-stop-signal (s/or :symbol symbol?
                                        :uuid uuid?
                                        :int integer?))
(s/def :frereth/world-connector
  (s/fspec :args (s/cat :world-stop-signal :frereth/world-stop-signal
                        :send-message! :frereth/message-sender!)
           :ret :frereth/renderer->client))
