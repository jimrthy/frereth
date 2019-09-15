(ns tracker.model.user
  (:require
   [fulcro.client.mutations :as m :refer [defmutation]]))

;; This ns is probably obsolete and should not be used

(defn user-path
  "Normalized path to a user entity or field in Fulcro state-map"
  ([id field] [:user/id id field])
  ([id] [:user/id id]))

(defn insert-user*
  "Insert a user into the correct table of the Fulcro state-map database."
  [state-map {:user/keys [id] :as user}]
  (assoc-in state-map (user-path id) user))

(defmutation upsert-user
  "Client Mutation: Upsert a user (full-stack. see CLJ version for server-side)."
  [{:user/keys [id level name rings]
    :as params}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     (insert-user* params)
                     (m/integrate-ident* [:user/id id] :append [:all-users])))))
  (remote [env] true))
