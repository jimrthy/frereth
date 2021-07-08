(ns tracker.model.player
  (:require
   [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
   [com.fulcrologic.fulcro.mutations :as m :refer [declare-mutation defmutation]]))

(defn player-path
  "Normalized path to a user entity or field in Fulcro state-map"
  ([id field] [:player/id id field])
  ([id] [:player/id id]))

(defn insert-player*
  "Insert a user into the correct table of the Fulcro state-map database."
  [state-map {:player/keys [id] :as player}]
  (assoc-in state-map (player-path id) player))

;; IF you declare your mutations like this, then you can use them WITHOUT quoting in the UI!
;; Note that the declaration should appear in other namespaces, with the symbol ns'd back
;; to this one.
;; Declaring this here is just redundant.
(comment (declare-mutation upsert-player `upsert-player))
(defmutation upsert-player
  "Client Mutation: Upsert a user (full-stack. see CLJ version for server-side)."
  [{:player/keys [id level name rings]
    :as params}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     (insert-player* params)
                     (targeting/integrate-ident* [:player/id id] :append [:all-players])))))
  (remote [env] true))
