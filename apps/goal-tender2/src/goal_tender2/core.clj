(ns goal-tender2.core
  "TODO: Everything in here needs to move"
  (:require [datomic.api :as d]))

(defn schema
  "Transactions to put schema into the data store
  This really belongs in something like a .EDN file"
  []
  [{:db/ident :todo/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/fulltext true
    :db/doc "Get the basic idea at a glance"}
   {:db/ident :todo/due-date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When must this task be done?"}
   {:db/ident :todo/just-a-dream
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Is this something that's just been jotted down, to be considered later?"}
   {:db/ident :todo/done
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Has user finished this line item yet?"}
   {:db/ident :todo/tag
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/index true
    ;; TODO: Run some performance measurements.
    ;; Does this have any meaningful impact?
    ;;:db/fulltext true
    :db/doc "Help keyword search"}
   ;; TODO: The entire point is that this needs to be a graph
   ;; I vaguely remember that a :db.cardinality/one :db.type/ref
   ;; is actually a 1-* relationship.
   ;; And that this is setting up a *-*.
   ;; Honestly, I'm not sure which makes the most sense here,
   ;; though the *-* could get confusing when you mark a grandparent
   ;; done...do cousins disappear from beneath the other grandparent?
   {:db/ident :todo/child
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    ;; Q: Is this worth indexing?
    :db/index true
    :db/doc "Because parent/child is an obvious relationship"}])

(defn connect
  [url]
  (d/create-database url)
  (d/connect url))

(defn install
  [schema conn]
  @(d/transact conn schema))

;; I have a defmethod sitting around somewhere for this.
;; TODO: Use that instead
(defn build-url
  [database-name]
  (str "datomic:free://localhost:4334/" database-name))

(defn do-installation
  [database-name]
  (let [url (build-url database-name)
        connection (connect url)]
    (install (schema) connection)
    connection))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
