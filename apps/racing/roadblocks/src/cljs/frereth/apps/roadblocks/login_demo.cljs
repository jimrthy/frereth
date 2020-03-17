(ns frereth.apps.roadblocks.login-demo
  "Provide an 'Attract' animation/demo

  And also a login mechanism so we can start the real thing

  That really conflats too very different ideas/states, but doesn't seem
  terrible as a starting point for proving the concept as a pre-auth
  demo"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.worker :as worker]
   [integrant.core :as ig]))

(defmethod ig/init-key ::worker
  [_ {:keys [::lamport/clock
             ::worker/manager]
      :as this}]
  ;; FIXME: Flesh this out
  this)
