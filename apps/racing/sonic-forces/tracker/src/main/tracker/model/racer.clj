(ns tracker.model.racer
  (:require
   [datomic.api :as d]
   [ghostwheel.core :as g :refer [>defn => <-]]
   ;; Q: What would be involved in switching to spec-alpha2?
   [clojure.spec.alpha :as s]))

(s/def :ability/description (s/keys :req [:ability/name :ability/description]))
(s/def :ability/category #{:ability/boost :abality/mine :ability/offense})
(s/def :ability/map (s/map-of :ability/category :ability/description))
(s/def :ability/score (s/and integer? pos? #(<= % 10)))
(s/def :archetype/class #{:archetype.class/common
                          :archetype.class/rare
                          :archetype.class/super-rare
                          :archetype.class/special})
;; FIXME: Mone this to a database ns and make it useful
(s/def :datomic/url string?)

(defn build-new-archetype-txn
  "This is really just the base foundation pieces"
  [racer-class archetype-name acceleration speed strength
   ability-score-map]
  (let [base
        {:archetype/name archetype-name
         :archetype/class :archetype.class/super-rare
         :archetype/acceleration acceleration
         :archetype/speed speed
         :archetype/strength strength}
        (throw (RuntimeException. "Need the ability map"))]))

(>defn add-archetype!
  [url
   racer-class archetype-name acceleration speed strength
   ability-map]
  [:datomic/url string? :ability/score]
  (let [cxn (d/connect url)
        txn (build-new-archetype-txn racer-class archetype-name acceleration speed strength)]
    (d/transact cxn txn)))
