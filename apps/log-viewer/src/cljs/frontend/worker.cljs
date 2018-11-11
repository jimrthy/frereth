(ns frontend.worker
  ;; This really should be a stand-alone project supplied by Server
  "Generate DOM structure and manage state"
  (:require [cljs.reader]
            [cognitect.transit :as transit]
            [common.hello :refer [foo-cljc]]
            [reagent.core :as r]))

(enable-console-print!)
(js/console.log "Worker top")

;; Reagent application state
;; Defonce used so that the state is kept between reloads
(defonce app-state (r/atom {:y 2017}))

(def log-entries (r/atom []))

(let [writer (transit/writer :json)]
  (defn render []
    ;; Keep in mind: web workers can absolutely open their own websockets.
    (try
      (let [dom [:div
                 [:h1 (foo-cljc (:y @app-state))]
                 [:table
                  [:tr [:th Number] [:th Time Received] [:th Body]]
                  ;; This approach doesn't work.
                  ;; Get an "Error: Cannot write Function"
                  ;; The nesting would have been wrong anyway.
                  (map-indexed (fn [index log-entry]
                                 [:tr
                                  [:td index]
                                  [:td (::time-stamp log-entry)]
                                  [:td (::body log-entry)]]))]
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
        ;; Almost certain we want to transfer ownership rather than
        ;; spending the time on a clone
        (.postMessage js/self (transit/write writer
                                             {:frereth/action :frereth/render
                                              :frereth/body dom})))
      (catch :default ex
        (console.error ex)))))
(render)

(set! (.-onerror js/self)
      (fn
        [error]
        (console.error error)
        ;; We can call .preventDefault on error to "prevent the default
        ;; action from taking place.
        ;; Q: Do we want to?
        (comment (.preventDefault error))))

(set! (.-onmessage js/self)
      (let [reader (transit/reader :json)]
        (fn
          [wrapper]
          (console.log "Worker received event" wrapper)
          (let [{:keys [:frereth/action]
                 :as data} (transit/read reader
                                         (.-data wrapper))]
            (condp = action
              :frereth/event
              (let [{[tag ctrl-id event] :frereth/body} data]
                (console.log "Should dispatch" event "to" ctrl-id "based on" tag)
                (try
                  (let [dirty?
                        (condp = tag
                          ::button-- (swap! app-state update :y dec)
                          ::button-+ (swap! app-state update :y inc)
                          ::button-log (console.log @app-state))]
                    (console.log "Dirty?" dirty?)
                    (when dirty? (render)))
                  (catch :default ex
                    (console.error ex))))
              :frereth/disconnect (do
                                    (console.log "World disconnected. Exiting.")
                                    (.close js/self))
              (swap! log-entries conj {::body data
                                       ::time-stamp (.now js/Date)}))))))

;; This needs to include the cooking that arrived with ::forking
;; FIXME: Start back here
(let [message {:frereth/action :frereth/forked}]
  (.postMessage js/self (transit/write (transit/writer :json) message)))

(console.log "Worker bottom")
