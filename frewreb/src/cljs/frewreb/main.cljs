(ns frewreb.main
  (:require [frewreb.system :as sys]))

(defn main []
  (let [dead-system (sys/init)]
    (sys/start dead-system)))

;;; Having this as a global seems really bad, but I'm not sure which
;;; alternatives would be better
(def system (main))
