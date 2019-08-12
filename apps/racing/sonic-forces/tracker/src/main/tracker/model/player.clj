(ns main.tracker.model.player
  (:require
    [com.wsscode.pathom.connect :as pc]
    [tracker.server-components.pathom-wrappers :refer [defmutation defresolver]]
    [taoensso.timbre :as log]))

(defonce player-database (atom {}))
(comment
  @player-database)

(defresolver all-players-resolver
  "Resolve queries for :all-users."
  [env input]
  {;;GIVEN nothing
   ::pc/output [{:all-players [:playerer/id]}]}                   ;; I can output all users. NOTE: only ID is needed...other resolvers resolve the rest
  (log/info "All users. Database contains: " @player-database)
  {:all-players (mapv
                 (fn [id] {:player/id id})
                 (keys @player-database))})

(defresolver player-resolver
  "Resolve details of a single player.  (See pathom docs for adding batching)"
  [env {:player/keys [id]}]
  {::pc/input  #{:player/id}                                  ; GIVEN a player ID
   ::pc/output [:player/name]}                                ; I can produce a player's details
  ;; Look up the player (e.g. in a database), and return what you promised
  (when (contains? @player-database id)
    (get @player-database id)))

(defmutation upsert-player
  "Add/save a player. Required parameters are:

  :player/id - The ID of the user
  :player/name - The name of the user

  Returns a Player (e.g. :player/id) which can resolve to a mutation join return graph.
  "
  [{:keys [config ring/request]} {:player/keys [id level name rings]}]
  {::pc/params #{:player/id :player/level :player/name :player/rings}
   ::pc/output [:player/id]}
  (log/debug "Upsert player with server config that has keys: " (keys config))
  (log/debug "Ring request that has keys: " (keys request))
  (when (and id name level rings)
    (if (< 0 level 17)
      (do
        (swap! player-database assoc id {:player/id   id
                                         :player/name name
                                         :player/level level
                                         :player/rings rings})
        ;; Returning the player id allows the UI to query for the result.
        {:player/id id})
      (throw (RuntimeException. "Q: How do I return a 400 error?")))))
