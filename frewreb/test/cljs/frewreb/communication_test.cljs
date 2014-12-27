;;; This namespace is used for testing purpose. It use the
;;; clojurescript.test lib.

;;; Since the module it's testing really involves external communication,
;;; it really isn't a good choice for unit testing
(ns frewreb.communication-test
  (:require-macros [cemerick.cljs.test :as m :refer (deftest testing are)])
  (:require [cemerick.cljs.test :as t]
            [frewreb.communication :as comms]))
(deftest foo-test
  (testing "I don't do a lot\n"))
