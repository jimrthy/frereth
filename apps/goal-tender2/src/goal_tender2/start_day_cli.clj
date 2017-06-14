(ns goal-tender2.start-day-cli
  "Systematically decide what to do today

Note that this is really a UI module.

I just realized that clojure doesn't have an
obvious base-line CLI input-string command, as
far as I've ever noticed."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [datomic.api :as d]
            [goal-tender2.catch :as catch]
            [goal-tender2.core :as g-t]))

(defn prompt-and-read
  ([ps1]
   (print (str ps1 "> "))
   (flush)
   (read-line))
  ([]
   (prompt-and-read \~)))

(defn brain-storm
  []
  (prompt-and-read "b-s"))

(defn brain-storm-loop
  [url]
  (let [dream (brain-storm)]
    (when-not (= ":quit" dream)
      (catch/add-dream url dream)
      (recur url))))

(defn do-it!
  [url dream]
  (comment (throw (RuntimeException. "FIXME: Write this")))
  (println "Should have been flagged to do something")
  ;; Be explicit about returning nil
  nil)

(defn dump-it!
  [url dream]
  (println "Should have been flagged to dump the dream")
  ;; Be explicit about returning nil
  nil)

(defn deliberate-it!
  [url dream]
  (println "Skip this dream to consider it later")
  ;; Be explicit about returning nil
  nil)

(defn decide
  [url]
  (let [dreams (catch/list-dreams url)]
    (loop [dream (first dreams)
           remainder (rest dreams)]
      (let [s (prompt-and-read (str dream
                                    "\n1. Do It 2. Dump It 3. Deliberate 4. Go back to brainstorming\n?"))
            d (edn/read-string s)]
        (if-let [selection
                 (case d
                   1 (do-it! url dream)
                   2 (dump-it! url dream)
                   3 (deliberate-it! dream)
                   4 (do
                       (brain-storm-loop url)
                       ;; Note that we really don't want to
                       ;; restart.
                       ;; We just want to add new dreams
                       ;; to the list
                       ::restart)
                   ::else-repeat)]
          (if (= selection ::else-repeat)
            (recur dream remainder)
            ;; This seems like it's already outgrown its usefulness
            (throw (RuntimeException. "How do I cope with that?")))
          (when (seq remainder)
            (recur (first remainder)
                   (rest remainder))))))))

(defn start-day
  [url]
  (println "Jot down your random ideas, one line at a time")
  (println "Enter :quit to move on to the next step")
  (brain-storm-loop url)
  (decide url))

(comment
  (start-day (g-t/build-url "sandcastle"))
  )
