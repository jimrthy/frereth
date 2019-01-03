(ns shared.lamport
  "Think about splitting a singleton clock into a Component"
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

(s/def :frereth/lamport integer?)

(s/def ::clock (s/and #?(:clj #(instance? clojure.lang.Atom %))
                      #?(:cljs #(= (type %) Atom))
                      ;; Q: Is s/conform more appropriate than s/valid?
                      ;; here?
                      #(s/conform :frereth/lamport (deref %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

;; First inclination to make this a Component seems very dubious.
;; There's no *real* side-effect to starting this, and no point
;; to stopping it.
;; The side-effects happen during tick.
;; On the other hand, this very much *is* a shared resource.

(defmethod ig/init-key ::clock
  [_ {:keys [::initial]
      :or {initial 0}}]
  (atom initial))

(s/fdef do-tick
  :args (s/or :local (s/cat :current ::clock)
              :sync (s/cat :current ::clock
                           :remote :frereth/lamport))
  :ret :frereth/lamport)
(defn do-tick
  ([current]
   (swap! current inc))
  ([current other]
   (swap! current
          (fn [actual]
            (inc (max actual other))))))
