(ns shared.specs
  "Specs that are generally useful among multiple namespaces"
  (:require [clojure.spec.alpha :as s]))

;; These are really anything that's
;; a) immutable (and thus suitable for use as a key in a map)
;; and b) directly serializable via transit
(s/def :frereth/pid any?)

(s/def ::time-in-state inst?)
