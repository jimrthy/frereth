(ns frereth.system
  (:refer-clojure :exclude (read))
  (:require [clojure.java.io :as io]
            [clojure.tools.reader :refer (read)]
            [clojure.tools.reader.reader-types :refer (indexing-push-back-reader)]
            [com.stuartsierra.component :as component :refer (system-map system-using)]
            [frereth-renderer.system :as renderer]
            [frereth-server.system :as server]))

(defn ^:private read-file
  [f]
  (read
   ;; This indexing-push-back-reader gives better information if the
   ;; file is misconfigured.
   (indexing-push-back-reader
    (java.io.PushbackReader. (io/reader f)))))

(defn ^:private config-from
  [f]
  (if (.exists f)
    (read-file f)
    {}))

(defn ^:private user-config
  []
  (config-from (io/file (System/getProperty "user.home") ".dareshi.edn")))

(defn ^:private config-from-classpath
  []
  (if-let [res (io/resource "dareshi.edn")]
    (config-from (io/file res))
    {}))

(defn ^:private default-config
  []
  {})

(defn config
  "Return a map of the static configuration used in the component
  constructors."
  []
  (merge (default-config)
         (config-from-classpath)
         (user-config)))

(defn new-base-system-map
  "Builds the system map.
The 2nd parameter is something that came from juxt.modular.
It's really just a record with a promise that get deliver'd
after the system is started.
Q: What's it for?"
  [config systemref]
  (system-map
   :done (promise)
   :renderer (renderer/init)
   :server (server/init)))

(defn new-base-dependency-map
  "Which components rely on which others?"
  [system-map]
  {})

(defn new-production-system
  "Create the production system"
  [systemref]
  (let [s-map (new-base-system-map (config) systemref)
        d-map (new-base-dependency-map s-map)]
    (with-meta
      (component/system-using s-map d-map)
      {:dependencies d-map})))
