(ns tracker.ui.root
  (:require
   [clojure.edn :as edn]
   [fulcro.client.dom :as dom :refer [div ul li p h3]]
   [fulcro.client.mutations :as m]
   [fulcro.client.primitives :as prim :refer [defsc]]
   [tracker.model.user :as user]
   [tracker.ui.components :as comp]
   [taoensso.timbre :as log]))

(defsc Player [this {:player/keys [id name level rings]
                     :as props}]
  {:query [:player/id :player/name :player/level :player/rings]
   :ident [:player/id :player/id]}
  (.log js/console "Rendering player from" props)
  (li :.ui.item {:id id}
    (div :.content
      (str name " (" level ") - " rings))))

(def ui-player (prim/factory Player {:keyfn :user/id}))

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
                     :max 16
                     :value (str level)})
     (dom/input #js {:id starting-level-spinner-id
                     :onChange (fn [evt]
                                 (m/set-string! this :player/level :event evt))
                     :type "number"
                     :min 1
                     :max 16
                     :value (str level)}))))
(def ui-player-level (prim/factory PlayerLevel))

(defsc PlayerAdder [this {player-name :player/name
                          :keys [:player/level
                                 :player/rings
                                 :ui/react-key]
                          :or {rings 0}}]
  {:ident [:ui/player-adder :ui/react-key]
   :initial-state (fn [{:keys [:ui/react-key]
                        :as initial-state-props}]
                    (.log js/console "Setting up initial state for PlayerAdder based on " initial-state-props)
                    {:player/name ""
                     :player/rings "0"
                     :ui/react-key react-key
                     :player/level (prim/get-initial-state PlayerLevel initial-state-props)})
   :query [:player/name :player/rings :ui/react-key
           {:player/level (prim/get-query PlayerLevel)}]}
  (let [new-name-id (str "new-player-name-" react-key)
        new-ring-id (str "new-player-ring-count-" react-key)]
    (div
     "Add New"
     (div
      (dom/label {:htmlFor new-name-id} "Name: ")
      (dom/input #js {:id new-name-id
                      ;; FIXME: This needs an onKeyDown to trigger the same mutation
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
      (dom/button :.ui.icon.button
                  #js {:onClick (fn []
                                  (let [id (str (random-uuid))]
                                    (log/info "Adding player")
                                    ;; NOTE: The lack of quoting works because we're using declare-mutation from incubator. see model.cljs
                                    ;; NOTE 2: This is a "mutation join".  The mutation is sent with a query, and on success it
                                    ;; returns the user.  This allows the server to tack on an address without us specifying it,
                                    ;; and also have the local database/ui update with it.
                                    (prim/transact! this [{(user/upsert-player {:player/id   id
                                                                                :player/level (-> level :player/level)
                                                                                :player/name player-name
                                                                                :player/rings rings})
                                                           (prim/get-query Player)}])
                                    (m/set-string! this :player/name :value "")))}
                  (dom/i :.plus.icon)
                  "Add Player")))))
(def ui-player-adder (prim/factory PlayerAdder))

(defsc Root [this {:keys [:all-players
                          :player-adder]}]
  {:query         [{:all-players (prim/get-query Player)}
                   {:player-adder (prim/get-query PlayerAdder)}]
   :initial-state (fn [_]
                    {:all-players []
                     :player-adder (prim/get-initial-state PlayerAdder {:ui/react-key 1})})}
  (div :.ui.segments
    (div :.ui.top.attached.segment
      (h3 :.ui.header
        "Welcome to Fulcro!")
      (p
        "This is full-stack demo content that shows off some of the power of Pathom when used with Fulcro.  The
        'add user' button will randomly generate a user's id/name, and transact it with the server via a mutation join
        (a transaction that can read after writing) which returns the
        newly created user with additional autogenerated server details (in this case address info).")
      (p
        "The resolvers and mutations are in model/user.clj(s).  The UI will query for all users on start, so after you
        add some, be sure to reload the page and see it come back.")
      (p
        "Make sure you've installed Fulcro Inspect, and your Chrome devtools will let you examine all of the details
        of the running tracker!"))
    (div :.ui.attached.segment
      (div :.content
        (div "Your system has the following players in the database:")
        (ul :.ui.list#player-list
            (map ui-player all-players)))
      (ui-player-adder player-adder))))
