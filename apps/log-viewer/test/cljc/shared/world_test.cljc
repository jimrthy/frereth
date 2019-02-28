(ns shared.world-test
  (:require [shared.world :as world]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])))

(t/deftest get-world-in-state
  (let [world-map {:a {::world/connection-state 1}
                   :b {::world/connection-state 1}
                   :c {::world/connection-state 2}}]
    (let [match (world/get-world-in-state world-map :a 1)]
      (t/is (= {::world/connection-state 1} match)))
    (let [miss (world/get-world-in-state world-map :b 2)]
      (t/is (nil? miss)))
    (let [miss (world/get-world-in-state world-map :d 2)]
      (t/is (nil? miss)))))

(t/deftest get-by-state
  (let [world-map {:a {::world/connection-state 1}
                   :b {::world/connection-state 1}
                   :c {::world/connection-state 2}}]
    (let [matches (world/get-by-state world-map 1)]
      (t/is (= (count matches) 2)))
    (let [match (world/get-by-state world-map 2)]
      (t/is (= (count match) 1)))))
