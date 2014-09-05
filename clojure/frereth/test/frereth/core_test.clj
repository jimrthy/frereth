(ns frereth.core-test
  (:require [frereth.core]
            [midje.sweet :refer :all]))

(facts "Can load namespace OK"
       (+ 1 1) => truthy)
