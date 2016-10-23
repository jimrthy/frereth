(ns goal-tender.installation
  (:require [com.jimrthy.substratum.core :as db]
            [com.jimrthy.substratum.platform :as platform]
            [datomic-schema.schema :nefer (generate-parts
                                           generate-schema)]))

(defn base-line
  "Set up a database w/ the 'data platform' basics"
  [uri-description]
  (throw (ex-info "Not implemented" {})))

(defn do
  [uri-description]
  (let [part-name "goal-tender"
        ;; Q: What goes in here?
        attribute-types [goal {children [:ref #{"sub-goals for breaking things into smaller steps"}]
                               ;; TODO: add comments
                               ;; But that really means adding users, which opens another
                               ;; can of worms with RBAC that is really a bigger scope
                               ;; question
                               completed [:instant #{"When was this goal marked done?"}]
                               creation [:instant #{"When was this goal created?"}]
                               depends-on [:ref #{"Other goals that must be accomplished first"}]
                               description [:string #{}]
                               related [:ref #{}]
                               ;; Note that this really needs to be a list
                               tags [:string #{"For "}]
                               title [:string #{"Short identifier"}]}
                         work-time {start [:instant]
                                    stop [:instant]
                                    goal [:ref]}]
        ;; Q: What really goes here?
        ;; The definition of actual objects based on those types?
        ;; (doesn't seem to make much sense)
        ;; Seed data?
        ;; The answer's in substratum's dev-resources/data-platform.edn
        ;; attribute-types define the root types that need to be defined
        ;; for everything else to work: :dt/dt and :dt.any
        ;; These are for the bulk of the associated attributes, like the
        ;; actual :dt/dt or instance
        attributes [{:db/id :com.frereth.app.goal-tender.datatype/entity
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
                                 :goal/title]}]
        txns {:com.jimrthy.substratum.platform/partitions part-name
              :com.jimrthy.substratum.platform/attribute-types attribute-types
              :com.jimrthy.substratum.platform/attributes attributes}]
    ;; TODO: Switch to install-schema-from-resource!
    (platform/install-schema! uri-description part-name txns)))
