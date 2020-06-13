(ns frereth.apps.shared.test-event-queue
  (:require [frereth.apps.shared.event-queue :as sut]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])))

;;;; TODO: Create a separate folder for tests

(t/deftest basic-event-dispatch
  (let [foo-call-count (atom 0)
        foo-handler (fn [_]
                      (swap! foo-call-count inc))
        e-q (sut/build!)
        ;; Set up 3 foo handlers
        {e-q ::sut/registrations} (sut/register e-q ::foo foo-handler)
        {e-q ::sut/registrations} (sut/register e-q ::foo foo-handler)
        {e-q ::sut/registrations} (sut/register e-q ::foo foo-handler)

        bar-count (atom 0)
        bar-handler (fn [_]
                      (swap! bar-count inc))
        {e-q ::sut/registrations} (sut/register e-q ::bar bar-handler)
        {e-q ::sut/registrations} (sut/register e-q ::bar bar-handler)]
    (sut/publish! e-q ::foo {::baz nil})
    (t/is (= @foo-call-count 3))
    (t/is (= @bar-count 0))

    (sut/publish! e-q ::bar ::quux)
    (t/is (= @foo-call-count 3))
    (t/is (= @bar-count 2))))

(t/deftest deregistration
  (let [e-q (sut/build!)
        foo-count (atom 0)
        foo-handler (fn [_] (swap! foo-count inc))
        {e-q ::sut/registrations
         foo-id-1 ::sut/handler-key} (sut/register e-q ::foo foo-handler)
        {e-q ::sut/registrations
         foo-id-2 ::sut/handler-key} (sut/register e-q ::foo foo-handler)

        bar-count (atom 0)
        bar-handler (fn [_] (swap! bar-count inc))
        {e-q ::sut/registrations
         bar-id-1 ::sut/handler-key} (sut/register e-q ::bar bar-handler)
        {e-q ::sut/registrations
         bar-id-2 ::sut/handler-key} (sut/register e-q ::bar bar-handler)]
    (sut/publish! e-q ::foo {::baz :anything})
    (t/is (= @foo-count 2))
    (t/is (= @bar-count 0))

    (sut/publish! e-q ::bar {::quux ::something-else})
    (t/is (= @foo-count 2))
    (t/is (= @bar-count 2))

    (let [e-q (sut/de-register e-q ::foo foo-id-1)]
      (sut/publish! e-q ::foo {::baz :anything})
      (t/is (= @foo-count 3))
      (t/is (= @bar-count 2))

      (sut/publish! e-q ::bar {::quux ::something-else})
      (t/is (= @foo-count 3))
      (t/is (= @bar-count 4))
      (let [e-q (sut/de-register e-q ::bar -1)]
        (sut/publish! e-q ::bar {::quux ::something-else})
        (t/is (= @foo-count 3))
        (t/is (= @bar-count 6))
        (let [e-q (sut/de-register e-q ::bar bar-id-1)]
          (sut/publish! e-q ::bar {::quux ::something-else})
          (t/is (= @foo-count 3))
          (t/is (= @bar-count 7)))))))
