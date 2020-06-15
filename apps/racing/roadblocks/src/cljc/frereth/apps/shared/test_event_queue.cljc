(ns frereth.apps.shared.test-event-queue
  (:require [frereth.apps.shared.event-queue :as sut]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])
            [#?(:clj clojure.core.async
                :cljs cljs.core.async) :as async]))

;;;; TODO: Create a separate folder for tests

(t/deftest basic-event-dispatch
  (let [e-q (sut/build)
        foo-call-count (atom 0)
        bar-count (atom 0)]
    (try
      (let [foo-handler (fn [_]
                          (swap! foo-call-count inc))
            bar-handler (fn [_]
                          (swap! bar-count inc))]
        ;; Set up 3 foo handlers
        (sut/register! e-q ::foo foo-handler)
        (sut/register! e-q ::foo foo-handler)
        (sut/register! e-q ::foo foo-handler)

        (sut/register! e-q ::bar bar-handler)
        (sut/register! e-q ::bar bar-handler)

        #_(sut/publish! e-q ::foo {::baz nil})
        #_(sut/publish! e-q ::bar ::quux)
        ;; This gets into implementation details
        ;; that should not be tested.
        ;; But I'm not sure where these messages are
        ;; disappearing
        (let [foo-pub (sut/publish! e-q ::foo {::baz nil})
              bar-pub (sut/publish! e-q ::bar ::quux)]
          (async/go
            (let [[foo-result ch-foo] (async/alts! [foo-pub (async/timeout 150)])
                  [bar-result ch-bar] (async/alts! [bar-pub (async/timeout 150)])]
              (t/is (= ch-foo foo-pub))
              (t/is (= ch-bar bar-pub))
              (println "Foo published:" foo-result
                       "\nBar published:" bar-result)))))

      (finally
        (async/go
          (sut/tear-down! e-q)
          (let [{:keys [::sut/event-loop]} e-q
                [_ ch] (async/alts! [(async/timeout 50) event-loop])]
            (t/is (= ch event-loop))
            (t/is (= @foo-call-count 3))
            (t/is (= @bar-count 2))))))))

(t/deftest deregistration
  (let [e-q (sut/build)
        foo-count (atom 0)
        bar-count (atom 0)]
    (let [foo-handler (fn [_] (swap! foo-count inc))
          bar-handler (fn [_] (swap! bar-count inc))

          foo-id-1 (sut/register! e-q ::foo foo-handler)
          foo-id-2 (sut/register! e-q ::foo foo-handler)

          bar-id-1 (sut/register! e-q ::bar bar-handler)
          bar-id-2 (sut/register! e-q ::bar bar-handler)]

      (async/go
        (println "Starting the publishing sequence")
        (try
          (async/<! (sut/publish! e-q ::foo {::baz :anything}))
          ;; Now that the event queue is async, it's subject to race
          ;; conditions.
          (t/is (= @foo-count 2))
          (t/is (= @bar-count 0))

          (async/<! (sut/publish! e-q ::bar {::quux ::something-else}))
          (t/is (= @foo-count 2))
          (t/is (= @bar-count 2))

          (sut/de-register! e-q ::foo foo-id-1)
          (async/<! (sut/publish! e-q ::foo {::baz :anything}))
          (t/is (= @foo-count 3))
          (t/is (= @bar-count 2))

          (sut/publish! e-q ::bar {::quux ::something-else})
          (t/is (= @foo-count 3))
          (t/is (= @bar-count 4))

          ;; Deregistering a handler that doesn't exist.
          ;; Should not have any effect
          (sut/de-register! e-q ::bar -1)
          (sut/publish! e-q ::bar {::quux ::something-else})
          (t/is (= @foo-count 3))
          (t/is (= @bar-count 6))

          (sut/de-register! e-q ::bar bar-id-1)
          (sut/publish! e-q ::bar {::quux ::something-else})
          (finally
            (sut/tear-down! e-q)
            (t/is (= @foo-count 3))
            (t/is (= @bar-count 7))))))))
