(ns frontend.core
  (:require-macros [frontend.macro :refer [foobar]])
  (:require [foo.bar]
            ;; Start by supporting this, since it's so popular
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

(defn sanitize-scripts
  "Convert scripting events to messages that get posted back to worker"
  [worker
   [tag attributes & body :as raw-dom]]
  ;; Need to walk the tree to find any/all scripting components
  ;; This is fine for plain "Form-1" components (as defined in
  ;; https://purelyfunctional.tv/guide/reagent) that just
  ;; return plain Hiccup.
  ;; It breaks down with "Form-2" functions that return functions.
  ;; It completely falls apart for "Form-3" complex components.

  ;; It's very tempting to just give up on any of the main
  ;; react wrappers and either write my own (insanity!) or
  ;; see if something like Matt-Esch/virtual-dom could possibly work.
  (when tag
    (console.log "Trying to sanitize" tag attributes body)

    [tag
     ;; Q: Is nil attributes legal?
     (when (map? attributes)
       (throw (js/Error. "Not Implemented")))
     (let [body
           (if (map? attributes)
             body
             (into [attributes] body))]
       (into []
             (map (partial sanitize-scripts worker)
                  body)))]))

(defn spawn-worker
  []
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
    ;; Q: Do web workers provide that degree of isolation?
    ;; Q: Web Workers are designed to be relatively heavy-
    ;; weight. Is it worth spawning a new one for each
    ;; world (which are also supposed to be pretty hefty).
    (let [worker (new window.Worker
                      "js/worker.js"
                      ;; Currently redundant:
                      ;; in Chrome, at least, module scripts are not
                      ;; supported on DedicatedWorker
                      #js{"type" "classic"})]
      (set! (.-onmessage worker)
            (fn [event]
              (let [new-dom-wrapper (.-data event)
                    _ (println (str "Trying to read '"
                                    new-dom-wrapper
                                    "' as EDN"))
                    raw-dom (cljs.reader/read-string new-dom-wrapper)
                    dom (sanitize-scripts worker raw-dom)]
                (println "Rendering DOM" (pr-str dom))
                (r/render-component [(constantly dom)]
                                    (js/document.getElementById "app")))))
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
