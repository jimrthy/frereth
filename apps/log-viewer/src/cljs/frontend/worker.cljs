(ns frontend.worker
  ;; This really should be a stand-alone project supplied by Server
  "Generate DOM structure and manage state"
  (:require [common.hello :refer [foo-cljc]]
            [reagent.core :as r]))

(enable-console-print!)
(js/console.log "Worker top")

;; Reagent application state
;; Defonce used to that the state is kept between reloads
(defonce app-state (r/atom {:y 2017}))

(defn main [self]
  ;; Keep in mind: web workers can absolutely open their own websockets.
  (try
    (let [dom [:div
               ;; Changes in here still aren't propagating to the browser correctly
               [:h1 (foo-cljc (:y @app-state))
                #_(str "Hello from hard-coding " (:y @app-state))]
               [:div.btn-toolbar
                [:button.btn.btn-danger
                 {:type "button"
                  ;; Allowing them to pass arbitrary code around
                  ;; like this would defeat the purpose
                  :on-click ::button-+  ; #(swap! app-state update :y inc)
                  }
                 "+"]
                [:button.btn.btn-success
                 {:type "button"
                  :on-click ::button--  ; #(swap! app-state update :y dec)
                  } "-"]
                [:button.btn.btn-default
                 {:type "button"
                  :on-click ::button-log  ; #(js/console.log @app-state)
                  }
                 "Console.log"]]]]
      ;; TODO: transit should be faster than EDN
      ;; Pretty definitely want to transfer ownership rather than
      ;; spending the time on a clone
      (.postMessage self (pr-str dom)))
    (catch :default ex
      (console.error ex))))
(main js/self)

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
        [event]
        ;; Q: What can we do with this?
        (console.log "Received event" (clj->js (.-data event)))))
(console.log "Worker bottom")
