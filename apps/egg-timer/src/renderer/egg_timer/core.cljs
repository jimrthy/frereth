(ns egg-timer.core)

(enable-console-print!)

(println "This text is printed from src/egg-timer/core.cljs. We are picking up changes automatically")

;; define your app data so that it doesn't get over-written on reload

(defonce app-state (atom {:text "Hello world!"}))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)
