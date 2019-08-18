(ns app.model.account-test
  (:require
   [datomic.api :as d]
    [tracker.server-components.pathom :refer [build-parser]]
    [tracker.model.account :as acct]
    [tracker.util :refer [uuid]]
    [clojure.test :refer [are deftest is]]
    [fulcro-spec.core :refer [specification provided behavior assertions component provided!]]
    [tracker.model.free-database :as db]
    [taoensso.timbre :as log]))

(defn safe-db-uri
  []
  (let [db-name (str (gensym "test"))]
    (str "datomic:mem://" db-name)))

(defn new-mem-connection
  ;; FIXME: This really should be the setUp portion of a test feature
  [db-uri]
  (d/create-database db-uri)
  (db/migrate! db-uri "resources/private/schema.edn")
  (d/connect db-uri))

(defn tear-down
  [db-uri]
  (d/delete-database db-uri))

(defn seeded-setup [db-uri]
  (let [conn (new-mem-connection db-uri)
        success (d/transact conn [{:account/id (uuid 1) :account/active? false}
                                  {:account/id (uuid 2) :account/active? true :account/email "account@example.net"}
                                  {:account/id (uuid 3) :account/active? true}])]
    {::conn conn
     ;; The txn has not necessarily been applied yet
     #_#_::db   (d/db conn)
     ::success-future success}))

(deftest verify-seeding
  (let [db-uri (safe-db-uri)]
    (try
      (let [{:keys [::success-future]} (seeded-setup db-uri)
            {:keys [:db-before :db-after
                    :tx-data]
             :as success} @success-future
            uuid-attributes (set (filter #(= (second %) 64) tx-data))]
        (is (= 3 (count uuid-attributes)))
        (is (contains? uuid-attributes (uuid 1)))
        (is (contains? uuid-attributes (uuid 2)))
        (is (contains? uuid-attributes (uuid 3)))
        (is (not= db-before  db-after))
        (is (not (:tx-data success))))
      (finally
        (tear-down db-uri)))))

(deftest all-account-ids-test
  (let [db-uri (safe-db-uri)]
    (try
      (let [{:keys [::success-future]} (seeded-setup db-uri)
            {:keys [:db-after :tx-data]
             :as success} @success-future
            db (d/db (d/connect db-uri))
            _ (println "Looking for active accounts among" db-after)
            ids (acct/all-account-ids db)]
        (is (= db-after db))
        (println "tx-data:" (mapv vector tx-data))
        (println "Active accounts:" ids)
        #_(let [raw
              (d/q '[:find [?v ...]
                     :where
                     [?e :account/active? true]
                     [?e :account/id ?v]]
                   db)]
            (is (not raw)))
        #_(let [direct (acct/q-all-account-ids db)]
          (is (not direct)))
        #_(is (not ids))
        (assertions
         "can find the active account IDs that are in the given database"
         (set ids) => #{(uuid 2) (uuid 3)}))
      (finally
        (tear-down db-uri)))))

(comment
  (def check-db-uri (safe-db-uri))
  check-db-uri
  (def test-db (seeded-setup check-db-uri))
  test-db
  (::success-future test-db)
  (deref (::success-future test-db))
  (let [conn (new-mem-connection check-db-uri)]
    conn)
  (try
    (let [{:keys [::success-future]}
          db (:db-after @success-future)
          _ (println "Looking for active accounts among" db)
          ids (acct/all-account-ids #_db (d/db (d/connect db-uri)))]
      (print "Active accounts:" ids))
    (finally
      (tear-down db-uri)))
  )

(deftest get-account-test
  (let [db-uri (safe-db-uri)]
    (try
      (let [{:keys [::db]} (seeded-setup)
            entity (acct/get-account db (uuid 2) [:account/email])]
        (assertions
         "can find the requested account details"
         entity => {:account/email "account@example.net"}))
      (finally
        (tear-down db-uri)))))

(deftest parser-integration-test
  (component "The pathom parser for the server"
             (let [db-uri (safe-db-uri)
                   {:keys [::conn]} (seeded-setup db-uri)]
               (try
                 (let [parser (build-parser conn)]
                   (assertions
                    "Pulls details for all active accounts"
                    (parser {} [{:all-accounts [:account/email]}])
                    => {:all-accounts [{}
                                       {:account/email "account@example.net"}]}))
                 (finally
                   (tear-down db-uri)))))

  (provided! "The database contains the account"
             (acct/get-account db uuid subquery) => (select-keys
                                                     {:account/id      uuid
                                                      :account/active? false
                                                      ;; This is a distinction between datomic and the
                                                      ;; original datascript.
                                                      ;; Trying to transact this should fail
                                                      :account/cruft   22
                                                      :account/email   "boo@bah.com"} subquery)

             (component "The pathom parser for the server"
                        (let [db-uri (safe-db-uri)
                              {:keys [conn]} (seeded-setup db-uri)]
                          (try
                            (let [parser (build-parser conn)]
                              (assertions
                               "Can pull the details of an account"
                               (parser {} [{[:account/id (uuid 2)] [:account/id :account/email :account/active?]}])
                               => {[:account/id (uuid 2)] {:account/id      (uuid 2)
                                                           :account/email   "boo@bah.com"
                                                           :account/active? false}})))))))
