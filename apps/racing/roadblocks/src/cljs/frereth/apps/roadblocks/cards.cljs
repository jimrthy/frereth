(ns frereth.apps.roadblocks.cards
  "This namespace contains devcards and tests, and is the entrypoint for
  both 'yarn cards' and 'yarn test'."
  (:require ["jsdom-global" :as jsdom-global]
            ;; Import all namespaces with cards here to load them.
            #_[frereth.apps.roadblocks.hello-cards]
            [frereth.utils.previews.frame :as previews]))

;; Set jsdom to mock a dom environment for node testing.
;; Q: Is this appropriate?
(jsdom-global)

(defn ^:export main
  "Start the component visualization UI."
  []
  ; Add a special class to the body to signal we're in devcards mode.
  ; We want to mostly use the same styles as the app, but might need to make
  ; some exceptions.
  (js/document.body.classList.add "using-devcards")
  (let [holder (.getElementById js/document "app")]
    (previews/start! holder)))
