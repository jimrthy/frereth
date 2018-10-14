(ns frontend.worker
  ;; This really should be a stand-alone project supplied by Server
  "Generate DOM structure and manage state"
  (:require [cljs.reader]
            [common.hello :refer [foo-cljc]]
            [reagent.core :as r]))

(enable-console-print!)
(js/console.log "Worker top")

;; Reagent application state
;; Defonce used to that the state is kept between reloads
(defonce app-state (r/atom {:y 2017}))

(defn main []
  ;; Keep in mind: web workers can absolutely open their own websockets.
  (try
    (let [dom [:div
               ;; Changes in here still aren't propagating to the browser correctly
               [:h1 (foo-cljc (:y @app-state))]
               [:div.btn-toolbar
                [:button.btn.btn-danger
                 {:type "button"
                  ;; Primitive parts of events will be forwarded here
                  ;; using these keywords as tags to identify them.
                  :on-click ::button-+
                  }
                 "+"]
                [:button.btn.btn-success
                 {:type "button"
                  :on-click ::button--
                  } "-"]
                [:button.btn.btn-default
                 {:type "button"
                  :on-click ::button-log
                  }
                 "Console.log"]]]]
      ;; TODO: transit should be faster than EDN
      ;; Almost certain we want to transfer ownership rather than
      ;; spending the time on a clone
      (.postMessage js/self (pr-str dom)))
    (catch :default ex
      (console.error ex))))
(main)

(set! (.-onerror js/self)
      (fn
        [error]
        (console.error error)
        ;; We can call .preventDefault on error to "prevent the default
        ;; action from taking place.
        ;; Q: Do we want to?
        (comment (.preventDefault error))))

(set! (.-onmessage js/self)
      (fn
        [wrapper]
        (console.log "Worker received event" wrapper)
        ;; Wrapper.data looks like:
        (let [[tag ctrl-id event :as data] (cljs.reader/read-string (.-data wrapper))]
          (console.log "Should dispatch" event "to" ctrl-id "based on" tag)
          (try
            (let [dirty?
                  (condp = tag
                    ::button-- (swap! app-state update :y dec)
                    ::button-+ (swap! app-state update :y inc)
                    ::button-log (console.log @app-state))]
              (console.log "Dirty?" dirty)
              (when dirty? (main)))
            (catch :default ex
              (console.error ex))))))
(console.log "Worker bottom")
