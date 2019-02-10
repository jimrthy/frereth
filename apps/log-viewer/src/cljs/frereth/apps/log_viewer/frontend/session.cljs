(ns frereth.apps.log-viewer.frontend.session
  (:require [cljs.core.async :as async]
            [clojure.spec.alpha :as s]
            [frereth.apps.log-viewer.frontend.socket :as web-socket]
            [integrant.core :as ig]
            ;; It seems highly likely that everything that's currently
            ;; in here will move there.
            [shared.connection :as connection]
            [shared.world :as world])
  (:require-macros [cljs.core.async.macros :as async-macros :refer [go]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; FIXME: Better spec.
(s/def ::path-to-fork string?)

(s/def ::world-atom (s/and #(= (type %) Atom)
                           #(s/valid? :frereth/worlds (deref %))))

;;; TODO: ::session-state
(s/def ::manager (s/keys :req [::path-to-fork
                               ::session-id
                               ;; TODO: Try to move anything that refers
                               ;; to this into session-socket
                               ::web-socket/sock
                               ::world-atom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Implementation

(defmethod ig/init-key ::manager
  [_ {:keys [:frereth/worlds]
      :as opts}]
  (atom (into {:frereth/worlds (or worlds {})}
              opts)))

(declare do-disconnect-all)
(defmethod ig/halt-key! ::manager
  [_ {:keys [::session-id
             :frereth/worlds]}]
  (do-disconnect-all worlds))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef do-disconnect-all
  :args (s/cat :this ::manager)
  :ret ::manager)
(defn do-disconnect-all
  [{:keys [::world-atom]
    :as this}]
  (swap! this ::world-atom
         (fn [worlds]
           (reduce (fn [world-map world-key]
                     (let [world (world/get-world world-map world-key)]
                       (world/trigger-disconnection! world)
                       (update world-map world-key
                               (fn [_]
                                 (world/disconnecting world-map world-key)))))
                   worlds
                   (keys worlds)))))
