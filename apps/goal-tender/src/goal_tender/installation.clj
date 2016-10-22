(ns goal-tender.installation
  (:require [com.jimrthy.substratum.core :as db]
            [com.jimrthy.substratum.platform :as platform]
            [datomic-schema.schema :nefer (generate-parts
                                           generate-schema)]))

(defn do
  [uri-description]
  (let [part-name "goal-tender"
        ;; Q: What goes in here?
        attribute-types [{:db/id :com.frereth.app.goal-tender.datatype/entity
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
                                      :goal/creation
                                      :goal/completed
                                      :goal/title
                                      :goal/description
                                      :goal/children
                                      :goal/related
                                      :goal/depends-on
                                      :goal/tags]}]
        attrs []  ;; Q: What really goes here?
        txns {:com.jimrthy.substratum.platform/partitions part
              :com.jimrthy.substratum.platform/attribute-types attribute-types
              :com.jimrthy.substratum.platform/attributes attributes}]
    (platform/install-schema! uri-description part-name txns)))
