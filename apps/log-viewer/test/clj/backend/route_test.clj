(ns backend.route-test
  (:require  [backend.web.routes :as routes]
             [backend.web.server :as server]
             [clojure.test :as t :refer [are is]]
             [io.pedestal.http.route :as route]))

(t/deftest check-matches
  (let [routes (routes/build-pedestal-routes nil (atom nil))]
    (are [path method expected] (= expected (:route-name (route/try-routing-for routes :prefix-tree path method)))
      "/" :get ::routes/default
      "/index" :get ::routes/default-index
      "/echo" :connect ::routes/echo
      "/echo" :delete ::routes/echo
      "/echo" :get ::routes/echo
      "/echo" :head ::routes/echo
      "/echo" :options ::routes/echo
      "/echo" :patch ::routes/echo
      "/echo" :post ::routes/echo
      "/echo" :put ::routes/echo
      "/echo" :trace ::routes/echo)))

;; These are almost more interesting than the matches
(t/deftest check-mismatches
  (let  [routes (routes/build-pedestal-routes nil (atom nil))]
    (are [path method] (nil? (route/try-routing-for routes :prefix-tree path method))
      "/" :post
      "/api" :get)))
