(ns goal-tender.installation
  (:require [com.jimrthy.substratum.core :as db]
            [com.jimrthy.substratum.installer :as installer]
            [datomic-schema.schema :nefer (generate-parts
                                           generate-schema)]))

(defn do
  [uri-description]
  (let [part-name "goal-tender"
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
                           description [:string #{}]
                           ;; This is really a variant on the depends-on Edges
                           related [:ref #{}]
                           start-after [:ref #{"Don't bother showing this as an option until its prerequisite is complete"}]
                           ;; Note that this really needs to be a list/set.
                           ;; (i.e. where do I set the cardinality?)
                           tags [:string #{"To make search/organization easier"}]
                           title [:string #{"Short identifier"}]}
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
        attributes [;; This was my original brain-storming about the App Entity.
                    ;; It doesn't belong in here. It's really part of the App
                    ;; framework.
                    ;; Installing this *does* need to install an Entity that
                    ;; matches this basic Type so the framework can find those
                    ;; pieces.
                    #_{:db/id :com.frereth.app.goal-tender.datatype/entity
                       :dt/dt :dt/dt
                       :dt/app-id "This should probably be a UUID generated at install-time"
                       :dt/namespace "goal-tender"
                       :dt/name "item"
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
                       :dt/fields [:dt/dt
                                   :goal/children
                                   :goal/completed
                                   :goal/creation
                                   :goal/depends-on
                                   :goal/description
                                   :goal/related
                                   :goal/tags
                                   :goal/title]}
                    {:db/id #db/id [:db.part/user]
                     :dt/dt :com.frereth.app.goal-tender/goal
                     :dt/namespace "goal-tender"
                     :dt/name "Goal"
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
                     :dt/fields [:goal/children
                                 :goal/completed
                                 :goal/creation
                                 :goal/depends-on
                                 :goal/description
                                 :goal/related
                                 :goal/start-after
                                 :goal/tags
                                 :goal/title]}
                    {:db/id #db/id [:db.part/user]
                     :dt/dt :com.frereth.app.goal-tender/work-time
                     :dt/namespace "goal-tender"
                     :dt/name "Work"
                     :dt/fields [:work-time/start
                                 :work-time/stop
                                 :work-time/goal]}]

        txns {:com.jimrthy.substratum.installer/attribute-types attribute-types
              :com.jimrthy.substratum.installer/attributes attributes}]
    ;; TODO: Switch to install-schema-from-resource!
    ;; That isn't all that useful for dev time. But it's the way Apps will
    ;; need to install themselves once it's time to switch to a release mode.
    ;; Need to come up with a way to make that change seamless
    (installer/install-schema! uri-description part-name txns)))
