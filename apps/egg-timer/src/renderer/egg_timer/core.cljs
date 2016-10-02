(ns egg-timer.core
  (:require [cljs.test :refer (testing is)]
            [sablono.core :as sab :refer-macros [html]])
  (:require-macros [devcards.core :refer (defcard deftest dom-node)]))

(enable-console-print!)

(println "This text is printed from src/egg-timer/core.cljs. We are picking up changes automatically")

;; define your app data so that it doesn't get over-written on reload

(defonce app-state (atom {:text "Hello world!"
                          :counter 0}))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  (swap! app-state update-in [:__figwheel_counter] inc)
)

(defcard state-watcher
  "Monitor program state"
  app-state)

(defcard incrementer
  (fn [data-atom owner]
    (sab/html [:div
               [:button {:onClick (fn []
                                    (swap! data-atom #(update % :counter inc)))} "+"]]))
  app-state)

(defcard decrementer
  (fn [data-atom owner]
    (sab/html [:div
               [:button {:onClick (fn []
                                    (swap! data-atom #(update % :counter dec)))} "-"]]))
  app-state)

(defcard displayer
  (fn [data-atom owner]
    (sab/html [:p (str (-> data-atom deref :counter))]))
  app-state)

(defcard example-dom-node
  "Should be able to wire up incremental-dom using this approach

Note that this approach does not automatically track changes to data-atom"
  (dom-node
   (fn [data-atom node]
     (set! (.-innerHTML node) (str "<p>Value: " #_(:counter @data-atom) @data-atom " (end)</p>"))))
  app-state)

(deftest example-test
  "Sample tests"
  (testing "Context 1"
    (is (= (+ 3 4 555) 7) "This should fail")
    (is (= (* 2 3) 6))
    "## Note the use of inline markdown")
  (testing "Context 2"
    (is (= (+ 3 4 5) 12) "This should pass")
    (testing "Nested Context"
      (is (= (- 12 5) 7)))))
