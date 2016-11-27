(ns goal-tender.installation
  (:require [com.jimrthy.substratum.core :as db]
            [com.jimrthy.substratum.installer :as installer]
            [datomic-schema.schema :nefer (generate-parts
                                           generate-schema)]))

(defn do
  [uri-description]
  (let [part-name "goal-tender"
        ;; These really are/should be namespaced.
        ;; The first map are all goal/foo, while the second map are work/bar
        ;; It looks like these actually should be keywords, so maybe that will work.
        ;; TODO: Look into this once merge conflicts are resolved
        attribute-types '[{children [:ref #{"sub-goals for breaking things into smaller steps"}]
                           ;; TODO: add an entity for comments
                           ;; But that really means adding users, which opens another
                           ;; can of worms with RBAC. That is really a bigger scope
                           ;; question
                           completed [:instant #{"When was this goal marked done?"}]
                           creation [:instant #{"When was this goal created?"}]
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
                           depends-on [:ref #{"Other goals that must be accomplished first"}]
                           description [:string #{"What is this goal really all about?"}]
                           ;; This is really a variant on the depends-on Edges
                           related [:ref #{}]
                           start-after [:ref #{"Don't bother showing this as an option until its prerequisite is complete"}]
                           ;; Note that this really needs to be a list/set.
                           ;; (i.e. where do I set the cardinality?)
                           tags [:string #{"To make search/organization easier"}]
                           title [:string #{"Short identifier"}]
                           work [:ref #{"To work-time instances. This is why I need to implement typed refs"}]}
                          {start [:instant #{}]
                           stop [:instant #{}]
                           goal [:ref #{}]}]
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
                                 :goal/title]}]

        txns {:com.jimrthy.substratum.installer/attribute-types attribute-types
              :com.jimrthy.substratum.installer/attributes attributes}]
    ;; TODO: Switch to install-schema-from-resource!
    ;; That isn't all that useful for dev time. But it's the way Apps will
    ;; need to install themselves once it's time to switch to a release mode.
    ;; Need to come up with a way to make that change seamless
    (installer/install-schema! uri-description part-name txns)))
