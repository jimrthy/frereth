(ns goal-tender2.core
  "TODO: Everything in here needs to move

Q: Where?
A: The framework that creates this App"
  (:require [datomic.api :as d]
            [goal-tender2.persist.schema :as schema]))

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

(defn do-schema-installation
  [database-name]
  (let [url (build-url database-name)
        connection (connect url)]
    (install (schema/schema) connection)
    connection))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
