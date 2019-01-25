(ns frontend.core-test
  (:require [cljs.test :as test :refer-macros [deftest is testing]]
            [frontend.core :as core]))

(deftest do-nothing-sanitation
  (let [doms [[:a {:href "http://github.com"} "Github"]
              [:p "Hello " [:em "World!"]]
              [:div
               [:h1 foo-1829]
               [:div.btn-toolbar
                [:button.btn.btn-danger
                 {:type "button"
                  ;; This would lead to sanitation
                  ;; :on-click :frontend.worker/button-+
                  }
                 "+"]
                [:button.btn.btn-success
                 {:type "button"
                  ;; :on-click :frontend.worker/button--
                  }
                 "-"]
                [:button.btn.btn-default
                 {:type "button"
                  ;; :on-click :frontend.worker/button-log
                  }
                 "Console.log"]]]]]
    (doseq [dom doms]
      (is (= dom (core/sanitize-scripts sanitized))))))
