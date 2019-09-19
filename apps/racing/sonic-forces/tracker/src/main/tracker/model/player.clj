(ns tracker.model.player
  (:require
   [com.wsscode.pathom.connect :as pc :refer [defmutation defresolver]]
   [taoensso.timbre :as log]))

(let [player-1-id #uuid "197e0b56-88be-4040-b4d2-ab6ebc91f6ac"
      player-1 {:player/id player-1-id
                :player/level 1
                :player/name "Slopoke"
                :player/rings 0}]
  (defonce player-database (atom {player-1-id player-1})))
(comment
  @player-database
  )

(defresolver all-players-resolver
  ;; Resolve queries for :all-players.
  [env input]
  {;;GIVEN nothing
   ::pc/output [{:all-players [:player/id]}]} ; I can output all players. NOTE: only ID is needed...other resolvers resolve the rest
  (log/info "All players. Database contains: " @player-database)
  {:all-players (mapv
                 (fn [id] {:player/id id})
                 (keys @player-database))})

(defresolver player-resolver
  ;; Resolve details of a single player.  (See pathom docs for adding batching)
  [env {:player/keys [id]}]
  {::pc/input  #{:player/id}                                  ; GIVEN a player ID
   ::pc/output [:player/level
                :player/name
                :player/rings]}                                ; I can produce a player's details
  ;; Look up the player (e.g. in a database), and return what you promised
  (get @player-database id))
(comment
  (player-resolver nil {:player/id "197e0b56-88be-4040-b4d2-ab6ebc91f6ac"})
  (get @player-database "197e0b56-88be-4040-b4d2-ab6ebc91f6ac")
  )

(defmutation upsert-player
  ;; Add/save a player. Required parameters are:

  ;; :player/id - The ID of the user
  ;; :player/name - The name of the user

  ;; Returns a Player (e.g. :player/id) which can resolve to a mutation join return graph.
  [{:keys [config ring/request]} {:player/keys [exp id level name rings stars]}]
  {::pc/params #{:player/exp :player/id :player/level :player/name :player/rings :player/stars}
   ::pc/output [:player/id]}
  (log/debug "Upsert player with server config that has keys: " (keys config))
  (log/debug "Ring request that has keys: " (keys request))
  (when (and id name level rings)
    (if (< 0 level 17)
      (do
        (swap! player-database assoc id {:player/exp exp
                                         :player/id   id
                                         :player/name name
                                         :player/level level
                                         :player/rings rings
                                         :player/stars stars})
        ;; Returning the player id allows the UI to query for the result.
        {:player/id id})
      (throw (RuntimeException. "Q: How do I return a 400 error?")))))
