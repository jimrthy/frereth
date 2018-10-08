(ns frontend.worker
  ;; This really should be a stand-alone project supplied by Server
  "Generate DOM structure and manage state"
  (:require [common.hello :refer [foo-cljc]]
            [reagent.core :as r]))

;; Reagent application state
;; Defonce used to that the state is kept between reloads
(defonce app-state (r/atom {:y 2017}))

(defn main []
  (let [dom [:div
             [:h1 (foo-cljc (:y @app-state))]
             [:div.btn-toolbar
              [:button.btn.btn-danger
               {:type "button"
                ;; Allowing them to pass arbitrary code around
                ;; like this would defeat the purpose
                :on-click #(swap! app-state update :y inc)} "+"]
              [:button.btn.btn-success
               {:type "button"
                :on-click #(swap! app-state update :y dec)} "-"]
              [:button.btn.btn-default
               {:type "button"
                :on-click #(js/console.log @app-state)}
               "Console.log"]]]]
    (js/postMessage dom)))

(defn onerror
  [error]
  (let [description (str error.filename
                         ":"
                         error.lineno
                         " -- "
                         error.message)]
    (console.error description))
  ;; We can call .preventDefault on error to "prevent the default
  ;; action from taking place.
  ;; Q: Do we want to?
  (comment (.preventDefault error)))

(defn onmessage
  [event]
  (console.log "Received event" (clj->js event)))
