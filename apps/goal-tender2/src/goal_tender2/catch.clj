(ns goal-tender2.catch
  "For jotting down ideas in the middle of the day

The slash/hack version tempts me to use a reference to datomic.api
here.

Honestly, avoiding that was the main reason I wrote substratum."
  (:require [datomic.api :as d]))

(defn add-dream
  "For jotting down a basic ideas

The top trouble with just using a URL like this
is that doing `with` exploration isn't transparent,
the way it should be."
  [url summary]
  (let [cxn (d/connect url)
        txn {:todo/summary summary
             :todo/just-a-dream true
             :todo/done false}]
    @(d/transact cxn txn)))

(defn list-dreams
  [url]
  (throw (RuntimeException. "not implemented")))
