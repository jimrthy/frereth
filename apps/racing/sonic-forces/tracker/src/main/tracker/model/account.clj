(ns tracker.model.account
  (:require
   #_[tracker.model.free-database :as db]
   [datomic.api :as d]
   [ghostwheel.core :refer [>defn => | ?]]
   [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]
   [taoensso.timbre :as log]
   [clojure.spec.alpha :as s]))

(defn q-all-account-ids
  [db]
  ;; Q: What does the ... mean here?
  ;; A: It returns a collection
  (d/q '[:find [?v ...]
         :where
         [?e :account/active? true]
         [?e :account/id ?v]]
       db))

(>defn all-account-ids
  "Returns a sequence of UUIDs for all of the active accounts in the system"
  [db]
  [any? => (s/coll-of uuid? :kind vector?)]
  (q-all-account-ids db))

(defresolver all-users-resolver [{:keys [db] :as env} input]
  {;;GIVEN nothing (e.g. this is usable as a root query)
   ;; I can output all accounts. NOTE: only ID is needed...other resolvers resolve the rest
   ::pc/output [{:all-accounts [:account/id]}]}
  (println "Querying" db "from" env "for all account IDs")
  {:all-accounts (mapv
                   (fn [id] {:account/id id})
                   (all-account-ids db))})

(>defn get-account [db id subquery]
       [any? uuid? vector? => (? map?)]
       (println "Pulling" subquery "from" db)
       (let [result
             (d/pull db subquery [:account/id id])]
         (println "Result:" result)
         result))

(defresolver account-resolver [{:keys [db] :as env} {:account/keys [id]}]
  {::pc/input  #{:account/id}
   ::pc/output [:account/email :account/active?]}
  (get-account db id [:account/email :account/active?]))

(def resolvers [all-users-resolver account-resolver])
