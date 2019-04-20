(ns renderer.handlers-test
  (:require
   [io.pedestal.http.route :as route]
   [io.pedestal.http.route.definition.table :as table-route]
   [renderer.handlers :as handlers]
   [clojure.set :as set]
   [clojure.test :as t :refer (is)]))

(t/deftest basic-routing
  (let [raw-routes (handlers/build-routes)
        ;; FIXME: Make this go away
        ;; Q: Why? Aside from the inconvenience.
        ;; We could model this as an HTTP POST, but then we need to
        ;; stick something like this verb inside the message body.
        ;; And the HTTP model...well, it's obviously very successful.
        custom-verbs #{:frereth/forward}
        verbs (set/union @#'table-route/default-verbs
                         custom-verbs)
        ;; This isn't trivial becuse of the custom verb
        ;; Can't quite just call (route/expand-routes) as we
        ;; normally would.
        processed-routes (table-route/table-routes {:verbs verbs}
                                                   raw-routes)]
    #_(t/are [path verb expected]
        (= expected
           (route/try-routing-for processed-routes :prefix-tree path verb))
        "/api/v1/forked" :post)
    (let [matched (route/try-routing-for processed-routes :prefix-tree
                                         "/api/v1/forking" :post)]
      (t/is matched)
      (let [interceptors (:interceptors matched)]
        (is interceptors)
        (is (pos? (count interceptors)))
        (let [handler (first interceptors)]
          (if handler
            (is (:enter handler) (str "Mising enter among"
                                      (keys handler)))
            (is handler
                ("No match found"))))))))

(t/deftest route-intercepter
  (let [raw-routes (handlers/build-routes)
        custom-verbs #{:frereth/forward}
        verbs (set/union @#'table-route/default-verbs
                         custom-verbs)
        processed-routes (table-route/table-routes {:verbs verbs}
                                                   raw-routes)
        router-intc (route/router processed-routes :map-tree)
        router (:enter router-intc)
        request {:path-info "/api/v1/forking"
                 :request-method :post}
        initial-context {:request request}
        routed (router initial-context)]
    (is not routed)))
