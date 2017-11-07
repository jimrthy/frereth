(ns crawl.core
  (:gen-class))

(defn d
  "Sum of rolling an m-sided die n times"
  [n m]
  (reduce (fn [acc i]
            (+ acc (inc (rand-int m))))
          0
          (range n)))

(defn game-builder
  "Q: What did I have in mind for this?"
  [])

(defn silly-default-dungeon-icons
  []
  [["#" "#" "#" "#" "#" "#" "#" "#" "#" "#"]
   ["#" " " " " " " " " " " " " " " ">" "#"]
   ["#" " " "#" "#" "#" "#" "#" "#" "#" "#"]
   ["#" " " " " " " " " " " "i" " " " " "#"]
   ["#" "#" "#" "#" "#" "#" "#" "#" " " "#"]
   ["#" " " " " " " " " " " " " " " " " "#"]
   ["#" " " "#" "#" "#" "#" "#" "#" "#" "#"]
   ["#" " " " " " " " " " " " " " " " " "#"]
   ["#" "#" "#" "#" "#" "#" "#" "#" " " "#"]
   ["#" "#" "#" "#" "#" "#" "#" "#" "<" "#"]])

(defn  new-dungeon
  "This needs to lay out a map.
  Although you don't want to generate the entire dungeon immediately at start.

  Q: Do you?"
  []
  ;; This data representation fails:
  ;; the basic idea obviously works, but I can't
  ;; map across rows of columns this way
  (hash-map (map #([::icon %])
                 (silly-default-dungeon-icons))))

(defn new-player
  []
  {::stats (apply hash-map (map (fn [stat]
                                  [stat (d 3 6)])
                                [::str ::int ::wis ::dex ::chr ::con]))
   ::name "J"
   ::class nil})

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

(defn map->str
  [{{:keys [::levels]} ::map
    :keys [::dungeon-level]
    :as game}]
  (let [{:keys [::dngn-map]
         :as cur-lvl} (get levels dungeon-level)]

    (->> dngn-map
         (map (fn [row]
                (map ::icon)))
         (map str))))

(defn draw!
  [game]
  (doseq [row (map->str game)]
    (println row)))

(defn -main
  "Run a game"
  [& args]
  (let [game (new-game)]
    ;; Q: What's a good way to handle this loop?
    ;; dorun over lazy seq seems obvious
    ;; This one seems easier, but it smells.
    ;; Then again, we basically have a REPL
    (loop [game (new-game)]
      (when game
        (draw! game)
        ()
        (recur (next-turn game))))))
