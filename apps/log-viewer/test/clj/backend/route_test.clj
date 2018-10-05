(ns backend.route-test
  (:require  [backend.routes :as routes]
             [bidi.bidi :as bidi]
             [clojure.test :as t]))

(t/deftest check-matches
  (t/is (= (:tag (bidi/match-route routes/routes "/echo"))
           ::server/echo))
  (t/is (= (:tag (bidi/match-route routes/routes "/"))
           ::server/index)))
