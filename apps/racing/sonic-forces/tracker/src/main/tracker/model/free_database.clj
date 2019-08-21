(ns tracker.model.free-database
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [datomic.api :as d]
   [mount.core :refer [defstate]]
   [taoensso.timbre :as log]))

;;; FIXME: Load this from the config
(def db-uri "datomic:free://localhost:4334/sf-tracker")

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
  (let [txn [{:account/email email
               :account/proto-password-bad password}]
        conn (d/connect db-uri)]
    (d/transact conn txn)))

(comment
  (bad-create-account! db-uri "foo@bar.baz" "pass")
  )

(defn bad-credentials-retrieval
  [db-uri email]
  ;; Treating db-uri as a global here feels very...dirty.
  ;; There *is* a db-connection cached inside the pathom parser.
  ;; It probably shouldn't be.
  (let [conn (d/connect db-uri)
        db (d/db conn)
        ;; Q: Does this make sense as a pull query?
        raw (d/q '[:find ?email ?password
                   :in $ ?email
                   :where [?e :account/email ?email]
                   [?e :account/proto-password-bad ?password]]
                 db email)
        record (first raw)]
    (log/info "Querying for username and password in" db
              "returned" raw)
    (when record
      {:email (first record)
       :password (second record)})))

(comment
  (bad-credentials-retrieval db-uri "foo@bar.baz")
  (bad-credentials-retrieval db-uri "noone@nowhere.org")
  (bad-credentials-retrieval db-uri "jamesgatannah@gmail.com")

  (let [conn (d/connect db-uri)
        db (d/db conn)
        raw (d/q '[:find ?e #_?password
                   :in $ ?email
                   :where [?e :account/email]
                ;;   [?e :account/proto-password-bad ?password]
                   ]
                 db "jamesgatannah@gmail.com")]
    raw)
  )
