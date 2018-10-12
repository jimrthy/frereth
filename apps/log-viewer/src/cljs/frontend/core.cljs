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
    (let [prefix (into [tag]
                       (when (map? attributes)
                         [(reduce
                            (fn [acc [k v]]
                              (let [s (name k)
                                    prefix (subs s 0 3)]
                                (assoc acc k
                                       (if (= "on-" prefix)
                                         (fn [event]
                                           (console.log "Posting" v "to web worker")
                                           ;; Failing experiments
                                           (comment
                                             ;; Can't POST the raw event
                                             (.postMessage worker [v #_event])
                                             ;; Can't serialize event this way

                                             (.postMessage worker (prn-str [v (js->clj event)]))
                                             (let [cloned (goog.object/forEach event
                                                                               ;; called for side-effects
                                                                               ;; Q: Is this really the way to go?
                                                                               ;; What's a solid idiomatic way to
                                                                               ;; handle this?
                                                                               (fn [val key obj]
                                                                                 ))]))
                                           (let [ks (.keys js/Object event)
                                                 ;; Q: Would this be worth using a transducer?
                                                 pairs (map (fn [k]
                                                              (let [v (aget event k)]
                                                                (when (or (not v)
                                                                          (boolean? v)
                                                                          (number? v)
                                                                          (string? v)
                                                                          ;; clojurescript has issues with the
                                                                          ;; js/Symbol primitive.
                                                                          ;; e.g. https://dev.clojure.org/jira/browse/CLJS-1628
                                                                          ;; For now, skip them.
                                                                          )
                                                                  [k v])))
                                                            ks)
                                                 pairs (filter identity pairs)
                                                 clone (reduce
                                                        (fn [acc [k v]]
                                                          (assoc acc k v))
                                                        {}
                                                        pairs)]
                                             (.postMessage worker (pr-str [v clone]))))
                                         v))))
                            {}
                            attributes)]))]
      (into prefix
            (let [body
                  (if (map? attributes)
                    body
                    (into [attributes] body))]
              (map (fn [content]
                     (if (vector? content)
                       (sanitize-scripts worker content)
                       content))
                   body))))))

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
                      "/js/worker.js"
                      ;; Currently redundant:
                      ;; in Chrome, at least, module scripts are not
                      ;; supported on DedicatedWorker
                      #js{"type" "classic"})]
      (set! (.-onmessage worker)
            (fn [event]
              (let [new-dom-wrapper (.-data event)
                    raw-dom (cljs.reader/read-string new-dom-wrapper)
                    dom (sanitize-scripts worker raw-dom)]
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
        ;; The worker pool is getting ahead of myself.
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
