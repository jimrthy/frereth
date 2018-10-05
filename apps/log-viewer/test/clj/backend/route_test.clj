(ns backend.route-test
  (:require  [backend.server :as server]
             [bidi.bidi :as bidi]
             [clojure.test :as t]))

(t/deftest check-matches
  (t/is (= (:tag (bidi/match-route server/routes "/echo"))
           ::server/echo))
  (t/is (= (:tag (bidi/match-route server/routes "/"))
           ::server/index)))
