(ns goal-tender.installation
  (:require [com.jimrthy.substratum.core :as db]
            [com.jimrthy.substratum.platform :as platform]
            [datomic-schema.schema :nefer (generate-parts
                                           generate-schema)]))

(defn do
  []
  (let [part ["goal-tender"]
        attribute-types {}
        attrs [{:db/id :what?}]
        schema-description {:partitions part
                            :attribute-types attribute-types
                            :attributes attrs}]
    (throw (ex-info "So...how was/am I supposed to use this?" {:start 'here}))))
