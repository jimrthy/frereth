(ns tracker.model.player
  (:require
   [fulcro.incubator.mutation-interface :as mi :refer [declare-mutation]]
   [fulcro.client.mutations :as m :refer [defmutation]]))

(defn player-path
  "Normalized path to a user entity or field in Fulcro state-map"
  ([id field] [:player/id id field])
  ([id] [:player/id id]))

(defn insert-player*
  "Insert a user into the correct table of the Fulcro state-map database."
  [state-map {:player/keys [id] :as player}]
  (assoc-in state-map (player-path id) player))

;; IF you declare your mutations like this, then you can use them WITHOUT quoting in the UI!
(declare-mutation upsert-player `upsert-player)
(defmutation upsert-player
  "Client Mutation: Upsert a user (full-stack. see CLJ version for server-side)."
  [{:player/keys [id level name rings]
    :as params}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     (insert-player* params)
                     (m/integrate-ident* [:player/id id] :append [:all-players])))))
  (remote [env] true))
