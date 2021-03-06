(ns frereth.apps.log-viewer.worker.core
  ;; This really should be a stand-alone project supplied by Server
  ;; This is in a weird space.
  ;; In a lot of ways, this needs to do DI to set up the browser side
  ;; of the World.

  ;; That's fair.
  ;; Any operating system injects a lot of boilerplate into most newly
  ;; forked processes.
  "Generate DOM structure and manage state"
  (:require [cljs.reader]
            [cognitect.transit :as transit]
            [common.hello :refer [foo-cljc]]
            [reagent.core :as r]))

(enable-console-print!)
(js/console.log "Worker top")

;; Reagent application state
;; Defonce used so that the state is kept between reloads
(defonce app-state (r/atom {:y 2017
                            ::log-entries []}))

;; Q: Worth adding a reference to the serializer and using that instead?
(let [writer (transit/writer :json)]
  (defn render []
    ;; Keep in mind: web workers can absolutely open their own websockets.
    (try
      (let [dom [:div
                 [:h1 (foo-cljc (:y @app-state))]
                 [:table
                  [:thead
                   [:tr [:th "Number"] [:th "Time Received"] [:th "Body"]]]
                  [:tbody
                   (map-indexed (fn [index log-entry]
                                  [:tr {:key index}
                                   [:td index]
                                   [:td (::time-stamp log-entry)]
                                   [:td (pr-str (::body log-entry))]])
                                (::log-entries @app-state))]]
                 [:div.btn-toolbar
                  [:button.btn.btn-danger
                   {:type "button"
                    ;; Primitive parts of events will be forwarded here
                    ;; using these keywords as tags to identify them.
                    :on-click ::button-+}
                   "+"]
                  [:button.btn.btn-success
                   {:type "button"
                    :on-click ::button--}
                   "-"]
                  [:button.btn.btn-default
                   {:type "button"
                    :on-click ::button-log}
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
      ;; FIXME: Switch to shared serialization library instead
      (let [reader (transit/reader :json)]
        (fn
          [wrapper]
          (console.log "Worker received event" wrapper)
          (let [{:keys [:frereth/action]
                 :as data} (transit/read reader
                                         (.-data wrapper))]
            (condp = action
              :frereth/disconnect (do
                                    (console.log "World disconnected. Exiting.")
                                    (.close js/self))
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
              :frereth/forward (console.warn "Worker should handle" data)
              (do
                (swap! app-state
                       #(update % ::log-entries
                                conj {::body data
                                      ::time-stamp (.now js/Date)}))
                (render)))))))

;; It seems like this should include the cookie that arrived with ::forking
;; It should not. We don't have any reason to know about that sort of
;; implementation detail here.
;; It would be nice to not even need to do this much.
;; On the other hand, it would also be really nice to have some sort of
;; verification that this is what we expected.
;; Then again, that *is* one of the main points behind TLS.
(let [message {:frereth/action :frereth/forked}]
  (.postMessage js/self (transit/write (transit/writer :json) message)))

(js/console.log "Worker bottom")
