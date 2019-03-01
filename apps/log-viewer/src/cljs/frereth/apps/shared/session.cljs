(ns frereth.apps.log-viewer.frontend.session
  ;; FIXME: Move this into shared
  (:require [cljs.core.async :as async]
            [clojure.spec.alpha :as s]
            ;; It seems highly likely that everything that's currently
            ;; in here will move into connection.
            [frereth.apps.shared.connection :as connection]
            [frereth.apps.shared.socket :as web-socket]
            [frereth.apps.shared.specs :as specs]
            [frereth.apps.shared.world :as world]
            [integrant.core :as ig])
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

(s/fdef add-pending-world
  :args (s/cat :this ::manager
               :full-pk :frereth/world-key
               :chan ::specs/async-chan
               :initial-state ::world/internal-state))
(defn add-pending-world
  [{:keys [::world-atom]
    :as this}
   full-pk ch initial-state]
  (swap! world-atom
         (fn [world-map]
           (world/add-pending world-map full-pk ch initial-state))))

(s/fdef do-disconnect-all
  :args (s/cat :this ::manager)
  :ret ::manager)
(defn do-disconnect-all
  [{:keys [::world-atom]
    :as this}]
  (println "Disconnecting all worlds in" @world-atom)
  (swap! (::world-atom this)
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

(s/fdef do-mark-forked
  :args (s/cat :this ::manager
               :world-key :frereth/world-key
               :worker :frereth/worker)
  :ret :frereth/worlds)
(defn do-mark-forked
  [{:keys [::world-atom]
    :as this}
   world-key worker]
  (swap! world-atom
         (fn [world-map]
           (world/mark-forked world-map world-key worker))))

(s/fdef do-mark-forking
  :args (s/cat :this ::manager
               :world-key :frereth/world-key
               ;; FIXME: Specs for these
               :cookie any?
               :raw-key-pair any?)
  :ret :frereth/worlds)
(defn do-mark-forking
  [{:keys [::world-atom]
    :as this} full-pk cookie raw-key-pair]
  (console.log "do-mark-forking: swap!ing the Cookie into the World")
  (swap! world-atom
         (fn [world-map]
           (world/mark-forking world-map full-pk cookie raw-key-pair))))
