(ns frontend.core
  (:require-macros [frontend.macro :refer [foobar]])
  (:require [foo.bar]
            [reagent.core :as r]
            [weasel.repl :as repl]))

(enable-console-print!)

(def idle-worker-pool
  "Workers were designed to be lightweight. How dangerous to share them?"
  (atom []))
(comment
  (println idle-worker-pool)
  (println (cljs->js @idle-worker-pool))
  (console.log (-> idle-worker-pool deref first))
  (.postMessage (-> idle-worker-pool deref first) ::abc)
  )

(defn spawn-worker
  []
  ;; goog is definitely defined here.
  ;; It becomes undefined when it starts trying to run worker.js.
  ;; Which seems promising: I don't want web workers to share
  ;; globals. (Research indicates the isolated global scope
  ;; is definitely expected).
  ;; But it's failing to load base.js, so goog remains undefined.
  ;; Overriding that to call self.importScripts rather than
  ;; importScripts didn't make any difference.
  (when window.Worker
    ;; This is missing a layer of indirection.
    ;; The worker this spawns should return a shadow
    ;; DOM that combines all the visible Worlds (like
    ;; an X11 window manager). That worker, in turn,
    ;; should spawn other workers that communicate
    ;; with it to supply their shadow DOMs into its.
    ;; In a lot of ways, this approach is like setting
    ;; up X11 to run a single app rather than a window
    ;; manager.
    ;; That's fine as a first step for a demo, but don't
    ;; get delusions of grandeur about it.
    ;; Actually, there's another missing layer here:
    ;; Each World should really be loaded into its
    ;; own isolated self-hosted compiler environment.
    ;; Then available workers should be able to pass them
    ;; (along with details like window location) around
    ;; as they have free CPU cycles.
    (let [worker (new window.Worker
                      "js/worker.js"
                      ;; Currently redundant:
                      ;; in Chrome, at least, module scripts are not
                      ;; supported on DedicatedWorker
                      #js{"type" "classic"})]
      (set! (.-onmessage worker)
            (fn [new-dom]
              (println "Rendering DOM")
              (r/render-component [(constantly new-dom)]
                                  (js/document.getElementById "app"))))
      worker)))

(defn start! []
  (js/console.log "Starting the app")

  (when-not (repl/alive?)
    (repl/connect "ws://localhost:9001"))

  (try
    (if (spawn-worker)
      (do
        ;; This is getting ahead of myself.
        ;; Part of the missing Window Manager abstraction mentioned above
        (swap! idle-worker-pool conj spawn-worker)
        (.log js/console "Worker spawned"))
      (.warn js/console "Spawning worker failed"))
    (catch :default ex
      (console.error ex))))

(when js/window
  (start!))

(comment
  ;; Macro test
  (foobar :abc 3)

  ;; Example of interop call to plain JS in src/cljs/foo.js
  (js/foo)

  (println "Console print check"))
