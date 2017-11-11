(ns goal-tender2.persist.schema
  "Keep schema definitions isolated from everywhere else"
  (:require [com.jimrthy.substratum.installer :as installer]))

(defn schema
  "Transactions to put schema into the data store
  This really belongs in something like a .EDN file"
  []
  (throw (RuntimeException. "Obsolete: need to move these into define-schema"))
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
   {:db/ident :todo.state/dreaming}
   {:db/ident :todo.state/todo}
   {:db/ident :todo.state/dumped}
   {:db/ident :todo.state/deferred}
   {:db/ident :todo.state/done}
   {:db/ident :todo/state
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Has user finished this line item yet?"}
   {:db/ident :todo/next-look
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When do you want to think about this again?"}
   ;; This next item seems like something we could profitably
   ;; cover in the State enum
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

(defn define-schema
  []
  ;; Note that the schema definitions above have quite a few more
  ;; pieces that need to be moved into here
  (let [attribute-types [#:com.frereth.app.goal-tender.datatype.goal{:children [:ref #{"sub-goals for breaking things into smaller steps"}]
                                                                     ;; TODO: add an entity for comments
                                                                     ;; But that really means adding users, which opens another
                                                                     ;; can of worms with RBAC. That is really a bigger scope
                                                                     ;; question
                                                                     :completed [:instant #{"When was this goal marked done?"}]
                                                                     :creation [:instant #{"When was this goal created?"}]
                                                                     ;; Honestly, this is a set of Edges between the actual Goal Nodes.
                                                                     ;; There are a variety of Edge types that could fit:
                                                                     ;; depends, relates (with comments), blocked-by (Q: what's the
                                                                     ;; difference between that and depends?), and start-after.
                                                                     ;; It seems like these should also be customizable, but that's
                                                                     ;; a fancier App.
                                                                     ;; start-after seems like an interesting case where I'm thinking
                                                                     ;; of a fairly arbitrary pool:
                                                                     ;; Goals B, C, and D start after Goal A.
                                                                     ;; Once A is done, pick one of B, C, and D. Say user chose C.
                                                                     ;; Now B and D really need to change their start-after to point to
                                                                     ;; C.
                                                                     :depends-on [:ref #{"Other goals that must be accomplished first"}]
                                                                     :description [:string #{"What is this goal really all about?"}]
                                                                     ;; This is really a variant on the depends-on Edges
                                                                     :related [:ref #{}]
                                                                     :start-after [:ref #{"Don't bother showing this as an option until its prerequisite is complete"}]
                                                                     ;; Note that this really needs to be a list/set.
                                                                     ;; (i.e. where do I set the cardinality?)
                                                                     :tags [:string #{"To make search/organization easier"}]
                                                                     :title [:string #{"Short identifier"}]
                                                                     :work [:ref #{"To work-time instances. This is why I need to implement typed refs"}]}
                         #:com.frereth.app.goal-tender.datatype.work{:start [:instant #{}]
                                                                     :stop [:instant #{}]
                                                                     :goal [:ref #{}]}]
        ;; Q: What really goes here?
        ;; The definition of actual objects based on those types?
        ;; (doesn't seem to make much sense)
        ;; Seed data?
        ;; The answer's in substratum's dev-resources/data-platform.edn
        ;; attribute-types define the root types that need to be defined
        ;; for everything else to work: :dt/dt and :dt.any
        ;; These are for the bulk of the associated attributes, like the
        ;; actual :dt/dt or instance

        attributes [{:db/id :com.frereth.app.goal-tender.datatype/work
                     :dt/dt :dt/dt
                     :dt/app-id "Same UUID as the goal. Don't want to specify this here."
                     :dt/namespace "goal-tender"
                     :dt/name "work"
                     :dt/fields [:work-time/start
                                 :work-time/stop
                                 :work-time/goal]}
                    {:db/id :com.frereth.app.goal-tender.datatype/goal
                     :dt/dt :dt/dt
                     :dt/app-id "This should probably be a UUID generated at install-time"
                     :dt/namespace "goal-tender"
                     :dt/name "goal"
                     ;; These are more attributes.
                     ;; Every installation needs to insert a UUID to keep them from
                     ;; conflicting with attributes with similar names/ideas from other
                     ;; apps.
                     ;; This is where the idea of having totally separate databases
                     ;; becomes very appealing.
                     ;; It also doesn't fly with datomic-free, and this pretty much
                     ;; has to work with that.
                     ;; So the actual attribute creation code needs to inject a UUID
                     ;; on the fly.
                     ;; Note that this is really missing a list of work-time instances

                     ;; These field names really need more
                     ;; namespacing to avoid collisions.
                     ;; That responsibility really belongs
                     ;; to the framework.
                     ;; That's a detail that really should be
                     ;; transparent to apps.
                     ;; Except for the ones that want/need
                     ;; to interop with other Apps.
                     ;; That's a bigger question than I want
                     ;; to tackle for a while.
                     :dt/fields [:dt/dt
                                 :goal/children
                                 :goal/completed
                                 :goal/creation
                                 :goal/depends-on
                                 :goal/description
                                 :goal/related
                                 :goal/start-after
                                 :goal/tags
                                 :goal/title]}]]
    #:com.jimrthy.substratum.installer{:attribute-types attribute-types
                                       :attributes attributes}))
