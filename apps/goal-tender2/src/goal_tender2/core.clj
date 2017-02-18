(ns goal-tender2.core
  "TODO: Everything in here needs to move"
  (:require [datomic.api :as d]))

(defn schema
  "Transactions to put schema into the data store
  This really belongs in something like a .EDN file"
  []
  ;; Q: What's a good way to implement sort order for
  ;; today's tasks?
  ;; TODO: Really want links to user for things like
  ;; authors and editors.
  ;; But that gets into a totally different area that
  ;; I really want frereth to provide.
  [{:db/ident :todo/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/fulltext true
    :db/doc "Get the basic idea at a glance"}
   {:db/ident :todo/done
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Has user finished this line item yet?"}
   {:db/ident :todo/next-look
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When do you want to think about this again?"}
   {:db/ident :todo/just-a-dream
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Is this something that's just been jotted down, to be considered later?"}
   {:db/ident :todo/due-date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When *must* this task be done?"}
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
    ;; Note that this is really very distinct from subtasks
    :db/doc "Because parent/child is an obvious relationship"}
   {:db/ident :todo/after
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    ;; Another where indexing seems questionable.
    ;; But I can definitely see searching for
    ;; "Which tasks are blocked by TaskN?"
    ;; Then again...if I have TaskN, I already
    ;; have the direct links.
    ;; So that makes it seem much less useful
    :db/index true
    :db/doc "Because lots of times I just want to do arbitrary things in a certain order"}
   ;; These next two really need to be part of a sliding scale.
   ;; 1-10 or 1-100 probably make the most sense.
   ;; Q: Worth adding database functions to enforce that?
   {:db/ident :todo/priority
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "How much better will your life be when this is completed?"}
   {:db/ident :todo/difficulty
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "How difficult will it be to complete this task?"}
   {:db/ident :todo/subtask
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index true
    ;; Q: Do I really want this to be a Component?
    :db/isComponent true
    :db/doc "Break big tasks into smaller ones"}
   {:db/ident :todo.subtask/next
    ;; Set this up as a linked list instead of array indexes
    ;; to make it easier to shuffle around and complete items in the middle
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    ;; Really can't see any point at all to indexing this
    :db/index true
    :db/doc "Because datomic doesn't have order built in"}
   {:db/ident :todo/note
    :db/valueType :db.type/ref
    :db/isComponent true
    :db/cardinality :db.cardinality/many
    :db/index false}
   {:db/ident :todo.note/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/fulltext true}])

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
