(ns frereth.apps.log-viewer.renderer.socket
  ;; Q: Is this different enough from the browser-side
  ;; version to justify *not* combining them into a cljc?
  ;; (They seem very likely to diverge as soon as we get
  ;; into the implementation. Especially creation).
  (:require [clojure.spec.alpha :as s]
            [manifold.stream :as strm]))

(s/def ::sock (s/and strm/sink?
                     strm/source?))

;; Q: Does this make any sense?
(s/def ::wrapper (s/keys :req [::sock]))
