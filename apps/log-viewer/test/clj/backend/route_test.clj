(ns backend.route-test
  (:require  [backend.web.routes :as routes]
             [backend.web.server :as server]
             [bidi.bidi :as bidi]
             [clojure.test :as t]))

(t/deftest check-matches
  (let [routes (routes/build-routes nil nil)]
    (t/is (= ::routes/echo
             (:tag (bidi/match-route routes "/echo"))))
    (t/is (= ::routes/index
             (:tag (bidi/match-route routes "/"))))))
