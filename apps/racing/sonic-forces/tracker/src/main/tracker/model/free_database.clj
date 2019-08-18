(ns tracker.model.free-database
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [datomic.api :as d]
   [mount.core :refer [defstate]]))

(defn not-neg?
  "This seems silly, but I don't think there's a builtin for #(>= % 0)"
  [n]
  (not (neg? n)))

(defn migrate!
  "Just run this manually from the repl"
  [db-uri schema-file]
  (let [schema (with-open  [r (io/reader "resources/private/schema.edn")
                            reader (java.io.PushbackReader. r)]
                 (edn/read reader))
        connection (d/connect db-uri)]
    (d/transact connection schema)))

(def raw-url "datomic:free://localhost:4334/sf-tracker")
(defstate url :start raw-url)

(comment
  raw-url
  (d/create-database raw-url)
  (let [connection (d/connect raw-url)]
    connection)
  (migrate! raw-url "resources/private/schema.edn")

  ;; TODO: Convert this to a unit test for sanity's sake
  (let [db-uri "datomic:mem://check"]
    (d/create-database db-uri)
    (let [schema
          (with-open [r (io/reader "resources/private/schema.edn")]
            (let [reader (java.io.PushbackReader. r)]
              (edn/read reader))
            (let []))
          conn (d/connect db-uri)]
      @(d/transact conn schema)))
  )

(defn bad-create-account!
  [db-uri email password]
  (let [txn {:player/email email
             :player/proto-password-bad password}
        conn (d/connect db-uri)]
    (d/transact conn txn)))

(defn bad-credentials-retrieval
  [db-uri email]
  (let [conn (d/connect db-uri)
        db (d/db conn)
        raw (d/q '[:find ?e ?password
                   :in $ ?email
                   :where [?e :account/email ?email]
                   [?e :account/proto-password-bad ?password]]
                 db email)
        record (first raw)]
    (when record
      {:email (first record)
       :password (second record)})))

(comment
  (bad-credentials-retrieval raw-url "noone@nowhere.org")
  )
