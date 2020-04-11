(ns frereth.apps.roadblocks.cards
  "This namespace contains devcards and tests, and is the entrypoint for
  both 'yarn cards' and 'yarn test'."
  (:require ["jsdom-global" :as jsdom-global]
            ; Import all namespaces with cards here to load them.
            [frereth.app.roadblocks.hello-cards]
            [nubank.workspaces.core :as ws]
            [nubank.workspaces.card-types.react :as ct.react]))

;; Set jsdom to mock a dom environment for node testing.
;; Q: Do I want to do this?
(jsdom-global)

(defonce init (ws/mount))

(defn ^:export main
  "Start the devcards UI."
  []
  ; Add a special class to the body to signal we're in devcards mode.
  ; We want to mostly use the same styles as the app, but might need to make
  ; some exceptions.
  (js/document.body.classList.add "using-devcards")
  ; Start the devcards UI.
  (start-devcard-ui!))
