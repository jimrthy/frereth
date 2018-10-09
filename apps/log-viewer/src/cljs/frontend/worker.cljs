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
    (let [dom '[:div
                [:h1 (foo-cljc (:y @app-state))]
                [:div.btn-toolbar
                 [:button.btn.btn-danger
                  {:type "button"
                   ;; Allowing them to pass arbitrary code around
                   ;; like this would defeat the purpose
                   ;:on-click ::button-+  ; #(swap! app-state update :y inc)
                   }
                  "+"]
                 [:button.btn.btn-success
                  {:type "button"
                   ;:on-click ::button--  ; #(swap! app-state update :y dec)
                   } "-"]
                 [:button.btn.btn-default
                  {:type "button"
                   ;:on-click ::button-log  ; #(js/console.log @app-state)
                   }
                  "Console.log"]]]]
      ;; TODO: transit should be faster than EDN
      ;; And transfer ownership rather than spending the time on a clone
      ;; These fail
      (comment (.postMessage js/self dom)  ; dom can't be cloned.
               ;; ditto
               (.postMessage js/self (clj->js dom))
               ;; frontend$worker$main.postMessage is not a function
               (.postMesage js/self (pr-str dom))
               ;; postMessage is undefined
               (postMessage (pr-str dom)))
      (.postMessage self (pr-str dom)))
    (catch :default ex
      (js/console.error ex))))
(main js/self)

(defn onerror
  [error]
  (let [description (str error.filename
                         ":"
                         error.lineno
                         " -- "
                         error.message)]
    (js/console.error description))
  ;; We can call .preventDefault on error to "prevent the default
  ;; action from taking place.
  ;; Q: Do we want to?
  (comment (.preventDefault error)))

(defn onmessage
  [event]
  (js/console.log "Received event" (clj->js event)))
(js/console.log "Worker bottom")
