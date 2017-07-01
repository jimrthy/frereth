(ns crawl.core
  (:gen-class))

(defn  new-dungeon
  []
  [])

(defn new-game
  ([]
   (new-game (new-dungeon)))
  ([dungeon]
   (new-game dungeon (new-player)))
  ([dungeon player]
   (new-game (game-builder) dungeon player))
  ([game dungeon player]
   (throw (ex-info "What happens here?"
                   {::game game
                    ::dungeon dungeon
                    ::player player}))))

(defn next-turn
  [game]
  (throw (RuntimeException. "Do stuff")))

(defn -main
  "Run a game"
  [& args]
  (let [game (new-game)]
    ;; Q: What's a good way to handle this loop?
    ;; dorun over lazy seq seems obvious
    ;; This one seems easier, but it smells.
    (loop [game (new-game)]
      (when game
        (recur (next-turn game))))))
