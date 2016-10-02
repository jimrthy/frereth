(ns egg-timer.core
  (:require-macros [devcards.core :refer (defcard dom-node)]))

(enable-console-print!)

(println "This text is printed from src/egg-timer/core.cljs. We are picking up changes automatically")

;; define your app data so that it doesn't get over-written on reload

(defonce app-state (atom {:text "Hello world!"
                          :counter 0}))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)

(defcard my-first-card
  "<h1>Devcards rock!</h1>")

(defcard hello
  "## Documentation

Markdown"
  {:object "of focus"}
  {}
  {})

(defcard state-watcher
  "Monitor program state"
  app-state)

(defcard example-dom-node
  "Should be able to wire up incremental-dom using this approach

Note that changing data-atom in the REPL does not show up in the browser
until you do something like change the documentation so the updates can register"
  (dom-node
   (fn [data-atom node]
     (set! (.-innerHTML node) (str "<p>Value: " #_(:counter @data-atom) @data-atom " (end)</p>"))))
  app-state)

(defcard button
  "Incrementer"
  (dom-node
   (fn [data-atom node]
     (set! (.-innerHTML node)
           ;; OK, how do I make onClick increment my counter?
           ;; (this is really why I need a DOM library)
           (str "<button onClick=\"alert('click')\">+</button>")))))
