(ns tracker.server-components.pathom
  (:require
   [clojure.core.async :as async]
   [com.wsscode.pathom.connect :as pc]
   [com.wsscode.pathom.core :as p]
   [com.wsscode.common.async-clj :refer [let-chan]]
   [datomic.api :as d]
   [mount.core :refer [defstate]]
   [taoensso.timbre :as log]
   [tracker.model.account :as acct]
   [tracker.model.free-database :as db]
   [tracker.model.session :as session]
   [tracker.server-components.config :refer [config]]))

(pc/defresolver index-explorer [env _]
  {::pc/input  #{:com.wsscode.pathom.viz.index-explorer/id}
   ::pc/output [:com.wsscode.pathom.viz.index-explorer/index]}
  {:com.wsscode.pathom.viz.index-explorer/index
   (-> (get env ::pc/indexes)
     (update ::pc/index-resolvers #(into [] (map (fn [[k v]] [k (dissoc v ::pc/resolve)])) %))
     (update ::pc/index-mutations #(into [] (map (fn [[k v]] [k (dissoc v ::pc/mutate)])) %)))})

(def all-resolvers [acct/resolvers session/resolvers index-explorer])

(defn preprocess-parser-plugin
  "Helper to create a plugin that can view/modify the env/tx of a top-level request.

  f - (fn [{:keys [env tx]}] {:env new-env :tx new-tx})

  If the function returns no env or tx, then the parser will not be called (aborts the parse)"
  [f]
  {::p/wrap-parser
   (fn transform-parser-out-plugin-external [parser]
     (fn transform-parser-out-plugin-internal [env tx]
       (let [{:keys [env tx] :as req} (f {:env env :tx tx})]
         (if (and (map? env) (seq tx))
           (parser env tx)
           {}))))})

(defn log-requests [{:keys [env tx] :as req}]
  (log/debug "Pathom transaction:" (pr-str tx))
  req)

(defn build-parser [db-uri]
  (let [db-connection (d/connect db-uri)
        real-parser (p/parallel-parser
                     {::p/env     {::p/reader               [p/map-reader pc/parallel-reader
                                                             pc/open-ident-reader p/env-placeholder-reader]
                                   ::p/placeholder-prefixes #{">"}}
                      ::p/mutate  pc/mutate-async
                      ::p/plugins [(pc/connect-plugin {::pc/register all-resolvers})
                                   (p/env-wrap-plugin (fn [env]
                                                        ;; Here is where you can dynamically add things to the resolver/mutation
                                                        ;; environment, like the server config, database connections, etc.
                                                        (assoc env
                                                               :connection db-connection  ; Q: why not just the uri?
                                                               :config config)))
                                   (preprocess-parser-plugin log-requests)
                                   p/error-handler-plugin
                                   p/request-cache-plugin
                                   (p/post-process-parser-plugin p/elide-not-found)
                                   p/trace-plugin]})
        ;; NOTE: Add -Dtrace to the server JVM to enable Fulcro Inspect query performance traces to the network tab.
        ;; Understand that this makes the network responses much larger and should not be used in production.
        trace?      (not (nil? (System/getProperty "trace")))]
    (fn wrapped-parser [env tx]
      (async/<!! (real-parser env (if trace?
                                    (conj tx :com.wsscode.pathom/trace)
                                    tx))))))

(defstate parser
  :start (build-parser db/db-uri))
