(ns goal-tender2.installation
  (:require [com.jimrthy.substratum.installer :as installer]
            [goal-tender2.persist.schema :as schema]))

(defn do
  [uri-description part-name]
  (throw (RuntimeException. "Needs spec"))
  (let [txns (schema/define-schema)]
    (installer/install-schema! uri-description part-name txns)))

(comment
  (let [part-name "goal-tender"]
    ;; FIXME: Needs a uri-description
    (do {} part-name)))
