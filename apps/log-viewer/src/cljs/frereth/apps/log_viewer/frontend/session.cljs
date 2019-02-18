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

(enable-console-print!)

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
  [_ {:keys [:frereth/world-atom]
      :as opts}]
  (assoc opts
         ::world-atom (or world-atom
                          (atom {}))))

(declare do-disconnect-all)
(defmethod ig/halt-key! ::manager
  [_ {:keys [::session-id
             ::world-atom]
      :as this}]
  (do-disconnect-all this))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defn add-pending-world
  [{:keys [::world-atom]
    :as this}
   full-pk ch cookie initial-state]
  (swap! world-atom
         (fn [world-map]
           (world/add-pending world-map full-pk ch cookie initial-state))))

(s/fdef do-disconnect-all
  :args (s/cat :this ::manager)
  :ret ::manager)
(defn do-disconnect-all
  [{:keys [::world-atom]
    :as this}]
  (println "Disconnecting all worlds in" @world-atom)
  (swap! this ::world-atom
         (fn [worlds]
           (reduce (fn [world-map world-key]
                     (let [world (world/get-world world-map world-key)]
                       (world/trigger-disconnection! world)
                       (update world-map world-key
                               (fn [_]
                                 (world/mark-disconnecting world-map world-key)))))
                   worlds
                   (keys worlds)))))

(s/fdef get-worlds
  :args (s/cat :this ::manager)
  :ret :frereth/worlds)
(defn get-worlds
  [{:keys [::world-atom]}]
  @world-atom)
