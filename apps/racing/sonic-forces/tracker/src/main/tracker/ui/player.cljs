(ns tracker.ui.player
  (:require
   [clojure.edn :as edn]
   [com.fulcrologic.fulcro.data-fetch :as df]
   [com.fulcrologic.fulcro.dom :as dom :refer [div ul li p h3]]
   [com.fulcrologic.fulcro.mutations :as m]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
   [taoensso.timbre :as log]
   [tracker.model.player :as player]
   [tracker.ui.components :as components]))

(defsc Player [this {:player/keys [id exp name level rings stars]
                     :as props}]
  {:query [:player/exp :player/id :player/name :player/level :player/rings :player/stars]
   :ident [:player/id :player/id]}
  (.log js/console "Rendering player from" props)
  (li :.ui.item {:id id}
    (div :.content
      (str name " (level " level ") - " rings " rings"))))

(def ui-player (comp/factory Player {:keyfn :player/id}))

(defsc PlayerLevel [this {:keys [:player/level
                                 :ui/react-key]}]
  {:ident [:ui/player-level :ui/react-key]
   :initial-state (fn [{:keys [:ui/react-key
                               :player/level]
                        :as initial-props
                        :or {level 1}}]
                    {:ui/react-key react-key
                     :player/level level})
   :query [:player/level :ui/react-key]}
  (let [starting-level-slider-id (str "new-player-starting-level-slider-" react-key)
        starting-level-spinner-id (str "new-player-starting-level-spinner-" react-key)]
    (dom/span
     (dom/label {:htmlFor starting-level-slider-id} "Initial Player Level: ")
     (dom/input #js {:id starting-level-slider-id
                     :onChange (fn [evt]
                                 (m/set-string! this :player/level :event evt))
                     :type "range"
                     :min 1
                     :max 15
                     :value (str level)})
     (dom/input #js {:id starting-level-spinner-id
                     :onChange (fn [evt]
                                 (m/set-string! this :player/level :event evt))
                     :type "number"
                     :min 1
                     :max 15
                     :value (str level)}))))
(def ui-player-level (comp/factory PlayerLevel))

(defsc PlayerAdder [this {player-name :player/name
                          :keys [:player/exp
                                 :player/level
                                 :player/rings
                                 :player/stars
                                 :ui/react-key]
                          :or {rings 0}}]
  {:ident [:ui/player-adder :ui/react-key]
   :initial-state (fn [{:keys [:ui/react-key]
                        :as initial-state-props}]
                    (.log js/console "Setting up initial state for PlayerAdder based on " initial-state-props)
                    {:player/exp "0"
                     :player/name ""
                     :player/rings "0"
                     :player/stars "0"
                     :ui/react-key react-key
                     :player/level (comp/get-initial-state PlayerLevel initial-state-props)})
   :query [:player/exp :player/name :player/rings :player/stars :ui/react-key
           {:player/level (comp/get-query PlayerLevel)}]}
  (let [new-exp-id (str "new-player-exp-" react-key)
        new-name-id (str "new-player-name-" react-key)
        new-ring-id (str "new-player-ring-count-" react-key)
        new-stars-id (str "new-player-stars-count-" react-key)]
    (div
     "Add New Player"
     (div
      (dom/label {:htmlFor new-name-id} "Name: ")
      (dom/input #js {:id new-name-id
                      ;; FIXME: This needs an onKeyDown so <Enter> triggers the same mutation
                      ;; as the "Add Player" button
                      :onChange (fn [evt]
                                  (m/set-string! this :player/name :event evt))
                      :type "text"
                      :value player-name}))
     (div
      (ui-player-level level))
     (div
      (dom/label {:htmlFor new-ring-id} "Starting Rings: ")
      (dom/input #js {:id new-ring-id
                      :onChange (fn [evt]
                                  ;; TODO: Verify that this is numeric and
                                  ;; not-negative
                                  (m/set-string! this :player/rings :event evt))
                      :min 0
                      :type "number"
                      :value (str rings)}))
     (div
      (dom/label {:htmlFor new-exp-id} "Starting EXP: ")
      (dom/input {:id new-exp-id
                  :onChange (fn [evt]
                              (m/set-string! this :player/exp :event evt))
                  :min 0
                  :type "number"
                  :value (str exp)}))
     (div
      (dom/label {:htmlFor new-stars-id} "Starting stars: ")
      (dom/input {:id new-stars-id
                  :onChange (fn [evt]
                              (m/set-string! this :player/stars :event evt))
                  :min 0
                  :type "number"
                  :value (str stars)}))
     (div
      (dom/button :.ui.icon.button
                  #js {:onClick (fn []
                                  (let [id (random-uuid)]
                                    (log/info "Adding player")
                                    ;; This is a "mutation join".  The mutation is sent with a query, and on success it
                                    ;; returns the player.  This allows the server to tack on an address without us specifying it,
                                    ;; and also have the local database/ui update with it.
                                    (comp/transact! this [{(player/upsert-player {:player/id   id
                                                                                  :player/level (-> level :player/level)
                                                                                  :player/name player-name
                                                                                  :player/rings (edn/read-string rings)})
                                                           (comp/get-query Player)}])
                                    (m/set-string! this :player/name :value "")))}
                  (dom/i :.plus.icon)
                  "Add Player")))))
(def ui-player-adder (comp/factory PlayerAdder))

(defsc Root [this {:keys [:all-players
                          :player-adder]
                   account-id :account/id
                   :as props}]
  {:ident [:account/id :account/id]
   :initial-state (fn [_]
                    {:account/id 1
                     :all-players []
                     :player-adder (comp/get-initial-state PlayerAdder {:ui/react-key 1})})
   :query         [{:all-players (comp/get-query Player)}
                   {:player-adder (comp/get-query PlayerAdder)}
                   :account/id]
   :route-segment ["main"]
   :will-enter (fn [app {account-id :account/id
                         :as route-params}]
                 (log/info "Will enter with route-params " route-params)
                 (dr/route-deferred [:account/id account-id]
                                    (fn []
                                      (df/load app
                                               ::root
                                               Root
                                               {:post-mutation `dr/target-ready
                                                :post-mutation-params {:target [:account/id account-id]}}))))}
  (div :.ui.segments
    (div :.ui.top.attached.segment
      (h3 :.ui.header
        "Welcome to Fulcro!")
      (p
        "This is full-stack demo content that shows off some of the power of Pathom when used with Fulcro.  The
        'add player' button will randomly generate a player's id and transact it with the server via a mutation join
        (a transaction that can read after writing) which returns the
        newly created player with additional autogenerated server details (in this case address info).")
      (p
        "The resolvers and mutations are in model/player.clj(s).  The UI will query for all players on start, so after you
        add some, be sure to reload the page and see it come back.")
      (p
        "Make sure you've installed Fulcro Inspect, and your Chrome devtools will let you examine all of the details
        of the running tracker!"))
    (div :.ui.attached.segment
      (div :.content
           (div (str "Your account ("
                     account-id
                     ") has the following players in the database:"))
        (ul :.ui.list#player-list
            (map ui-player all-players))))
    (div :.ui.attached.segment
         (ui-player-adder player-adder))))
