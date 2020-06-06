(ns frereth.apps.shared.window-manager
  "Wrapper around individual end-user's window managers"
  (:require
   [clojure.spec.alpha :as s]
   [frereth.apps.shared.lamport :as lamport]
   [frereth.apps.shared.worker :as worker]
   ;; It isn't fair to enforce any given Component architecture at this
   ;; level.
   ;; FIXME: Need to come up with a better approach to System creation.
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Globals

(enable-console-print!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Specs

;; All the circularity and self-references in these specs makes me very
;; nervous.
(s/def ::opts (s/keys :req [::factory
                            ::lamport/clock
                            ::worker/manager]))

;; FIXME: This needs its own set of specs.
;; Most/all of them are functions.
;; This is really the interface that the window manager uses to
;; interact with:
;; a) the underlying System (I think xlib and xcb are both appropriate
;; examples for the level at which I want this to operate)
;; b) the individual Worlds
;; The concrete implementation in roadblocks.window-manager may be a
;; reasonable start, once it works
(s/def ::implementation any?)

(s/def ::interface (s/merge ::opts
                            (s/keys :opt [::implementation])))

(s/def ::ctor
  (s/fspec :args (s/cat :interface ::interface)
           :ret ::implementation))

(s/def ::dtor!
  (s/fspec :args (s/cat :interface ::interface)
           ;; Called for cleanup side-effects
           :ret any?))

(s/def ::factory (s/keys :req [::ctor
                               ;; It's tempting to make this optional.
                               ;; But, at the very least, it needs to
                               ;; notify its lone world that it's time
                               ;; to shut down
                               ::dtor!]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation
;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(defmethod ig/init-key ::interface
  [_ {{:keys [::ctor]} ::factory
      :as this}]
  (if ctor
    (assoc this ::implementation
           (ctor this))
    (throw (ex-info "Missing factory constructor for the interface among" this))))

(defmethod ig/halt-key! ::interface
  [_ {:keys [::implementation]
      {:keys [::dtor!]} ::factory}]
  (when implementation
    ;; Should probably have a way to verify that the interface has not
    ;; already been halted.
    ;; Hopefully integrant already covers that.
    ;; That hope collapses if I roll my own system management tool<
    (dtor! implementation)))
