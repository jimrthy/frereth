(ns frereth.apps.roadblocks.hello)

(throw (ex-info "This just doesn't fit"))

(defn click-counter [click-count]
  [:div
   "The atom " [:code "click-count"] " has value: "
   @click-count ". "
   [:input {:type "button" :value "Click me!"
            :on-click #(swap! click-count inc)}]])

(def click-count (atom 0))

(defn hello []
  [:<>
   [:p "Hello, roadblocks is running!"]
   [:p "Here's an example of using a component with state:"]
   [click-counter click-count]])
