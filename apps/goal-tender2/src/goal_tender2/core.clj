(ns goal-tender2.core
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
    :db/doc "Get the basic idea at a glance"}])

(defn connect
  [url]
  (d/create-database url)
  (d/connect url))

(defn install
  [schema conn]
  @(d/transact conn schema))

(defn do-installation
  [database-name]
  (let [url (str "datomic:free://localhost:4334/" database-name)
        connection (connect url)]
    (install (schema) connection)
    connection))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
