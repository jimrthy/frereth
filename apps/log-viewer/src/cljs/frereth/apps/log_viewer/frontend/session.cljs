(ns frereth.apps.log-viewer.frontend.session
  (:require [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.socket :as web-socket]
            [integrant.core :as ig]
            [shared.world :as world]))

(s/def ::manager (s/keys :req [::web-socket/sock
                               :frereth/worlds]))

(defmethod ig/init-key ::manager
  [_ opts]
  (atom (into {::web-socket/sock (web-socket/wrapper)
               :frereth/worlds {}}
              opts)))

(defmethod ig/halt-key! ::manager
  [_ {:keys [:frereth/worlds
             ::web-socket/sock]}]
  (when worlds
    (throw (js/Error "Need to disconnect each World")))
  (when sock
    (web-socket/close sock)))
