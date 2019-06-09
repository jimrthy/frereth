(ns user
  (:require [aleph.netty :refer (AlephServer)]
            [aleph.udp :as udp]
            [backend.main :as main]
            [backend.web.routes :as routes]
            [byte-streams :as b-s]
            [cider.piggieback]
            [client.networking :as client-net]
            [client.registrar :as registrar]
            [cljs.repl.browser :as cljs-browser]
            [clojure.core.async :as async]
            [clojure
             [data :as data]
             [edn :as edn]
             [pprint :refer (pprint)]
             [reflect :as reflect]]
            [clojure.java.io :as io]
            [clojure.repl :refer (apropos dir doc pst root-cause source)]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as test]
            [clojure.test.check :refer (quick-check)]
            [clojure.test.check
             [clojure-test :refer (defspec)]
             [generators :as lo-gen]
             [properties :as props]
             [generators :as lo-gen]]
            ;; These are moderately useless under boot.
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [frereth.apps.shared.connection :as connection]
            [frereth.cp
             [message :as msg]
             [shared :as cp-shared]]
            [frereth.cp.client.state :as client-state]
            [frereth.cp.shared
             [bit-twiddling :as b-t]
             [specs :as shared-specs]
             [util :as util]]
            [frereth.weald
             [logger-macro :as l-mac]
             [logging :as log]
             [specs :as weald]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all] :as ig-repl]
            [integrant.repl.state :as ig-state]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.table :as table-route]
            [manifold
             [deferred :as dfrd]
             [stream :as strm]]
            [renderer.handlers :as renderer-handlers]
            [renderer.lib :as renderer]
            [renderer.sessions :as sessions]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Magic Constants

(def server-port 32156)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Client

(defn build-client-config
  [server]
  {::weald/logger (-> ig-state/system ::weald/logger ::weald/logger)
                   ::shared-specs/port server-port
                   ::shared-specs/public-long   (-> server
                                                    :server.networking/server
                                                    :server.networking/cp-server
                                                    ::cp-shared/my-keys
                                                    ::cp-shared/long-pair
                                                    .getPublicKey)
   :backend.system/socket-opts {::shared-specs/port 41428}})

(defn build-client-description
  [server]
  (require 'backend.system)
  (let [config (build-client-config server)
        ctor (resolve 'backend.system/client-ctor)]
    (ctor config)))

(defn start-client
  [server]
  (let [client-description (build-client-description server)]
    (ig/init client-description)))

(comment
  (def client-description (build-client-description server))
  client-description
  (-> client-description :client.networking/connection ::weald/logger keys)
  (def client (ig/init client-description))
  (ig/halt! client)
  client
  (class client)
  (nil? client)
  (+ 1 3)
  (-> client :client.networking/connection ::client-state/state keys)
  (-> client :client.networking/connection ::client-state/state ::weald/state)
  (-> client :client.networking/connection ::client-state/state ::weald/logger :subordinates
      (get 0))
  (-> client :client.networking/connection ::client-state/state ::weald/logger)

  (let [log-state (-> client :client.networking/connection ::client-state/state ::weald/state)
        logger (-> client :client.networking/connection ::client-state/state ::weald/logger)]
    (log/flush-logs! logger
                     (log/debug log-state
                                ::repl
                                "Where is this going?")))
  (let [ch (-> client :client.networking/connection ::client-state/state ::weald/logger :subordinates
               (get 0) :channel)
        entry (l-mac/build-log-entry ::repl
                                     (-> client :client.networking/connection ::client-state/state ::weald/state ::weald/lamport)
                                     ::weald/debug
                                     "What about an entry build by hand?")
        cb (fn [result?]
             (println "Put result:" result?))]
    (async/put! ch entry cb))

  (let [ch (-> client :client.networking/connection ::client-state/state ::weald/logger :subordinates
               (get 0) :channel)]
    (async/alts!! [ch (async/timeout 100)] :default ::beat-timeout))
  )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Server

(defn build-server-cfg
  [monitor]
  (require 'backend.system :reload)
  (let [ctor (resolve 'backend.system/server-ctor)]
    (ctor monitor)))

(defn build-server
  [monitor]
  (let [config (build-server-cfg monitor)]
    (ig/init config)))

(comment
  ;; Sample code for exploring what we have now
  ig-state/system
  (keys ig-state/system)
  (-> ig-state/system
      :backend.system/log-chan
      :backend.system/ch)
  (async/put! (-> ig-state/system
                  :backend.system/log-chan
                  :backend.system/ch)
              "log message"
              (fn [success?]
                (println "Putting log message succeeded?" success?)))
  (-> ig-state/system
      :client.propagate/monitor
      :client.propagate/registration-handler)

  ;; UDP port creation blocks when a dangling socket retains control.
  ;; Experiment to see what the smallest use-case really looks like.
  (def sample-port (udp/socket {:port 47659}))
  @sample-port
  (.close @sample-port))

;;; To actually put that to use and create a server
(comment
  (def server-cfg (build-server-cfg ig-state/system))
  server-cfg
  (keys server-cfg)
  (def server (build-server (assoc server-cfg
                                   :backend.system/socket {:backend.system/server-port server-port})))
  server
  (keys server)
  ;; Send a basic log message
  (let [logger (::weald/logger server)
        log-state (log/init ::repl-test)]
    (log/flush-logs! logger (log/debug log-state ::repl "Just checking"))))
(comment
  ;; Reflect into system
  (:server.networking/server server)
  (-> server :server.networking/server keys)
  (-> server :server.networking/server :server.networking/cp-server keys)
  (-> server :server.networking/server :server.networking/cp-server ::cp-shared/my-keys ::cp-shared/long-pair .getPublicKey vec)
  (ig/halt! server)

  @registrar/registry-1)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Upper Management

(defn cljs-repl
  ;; The last time I actually tried this, it opened a new browser window with
  ;; instructions about writing an index.html.

  ;; In practice, under cider and emacs, running cider-connect-sibling-cljs
  ;; and choosing the weasel REPL type, then reloading the web page works fine.

  ;; This seems worth keeping around as a starting point for the sake of
  ;; anyone who isn't using emacs.
  "In theory, this launches a REPL that interacts with a new browser window"
  []
  (cider.piggieback/cljs-repl (cljs-browser/repl-env)
                              :host "0.0.0.0"
                              :launch-browser false
                              :port 9001))

(defn initialize
  [opts]
  (require 'backend.system)
  (let [ctor (resolve 'backend.system/monitoring-ctor)]
    (println "Root initialization")
    (ctor opts)))

(defn setup-monitor! [opts]
  (ig-repl/set-prep! #(initialize opts)))

(println "Run (setup-monitor! {:backend.system/routes {::routes/debug? true}
                 :backend.system/web-server {:backend.web.service/debug? true}})
 and then (go) to start the Monitor")

(comment
  (setup-monitor! {:backend.system/routes {::routes/debug? true}
                   :backend.system/web-server {:backend.web.service/debug? true}})
  (go)
  (halt)

  (-> ig-state/system keys)
  (-> ig-state/system :renderer.sessions/session-atom)
  (-> ig-state/system :renderer.sessions/session-atom deref keys)
  (-> ig-state/system :renderer.sessions/session-atom deref pprint)
  (-> ig-state/system :renderer.sessions/session-atom deref vals first)
  (-> ig-state/system :renderer.sessions/session-atom deref vals first keys)
  (-> ig-state/system :renderer.sessions/session-atom deref vals first ::connection/state)
  (-> ig-state/system :renderer.sessions/session-atom deref vals first :frereth/worlds)

  (-> ig-state/system :renderer.sessions/session-atom deref vals first :renderer.handlers/disconnected)
  (-> ig-state/system :renderer.sessions/session-atom deref vals first :renderer.handlers/forked)
  (-> ig-state/system :renderer.sessions/session-atom deref vals first :renderer.handlers/forked (strm/put!))
  (def posted
    (-> ig-state/system
        :renderer.sessions/session-atom
        deref
        vals
        first
        :renderer.handlers/forked
        (strm/try-take! 500)))
  posted
  (-> ig-state/system :renderer.sessions/session-atom deref vals first :renderer.handlers/forking)

  (-> ig-state/system :backend.event-bus/event-bus)
  (-> ig-state/system :backend.web.service/web-service keys)
  (-> ig-state/system :backend.web.service/web-service :io.pedestal.http/interceptors)
  (->> ig-state/system :backend.web.service/web-service :io.pedestal.http/interceptors (map :name))
  (-> ig-state/system :backend.web.service/web-service :io.pedestal.http/interceptors first)
  (-> ig-state/system :backend.web.service/web-service :io.pedestal.http/interceptors (nth 4))
  (-> ig-state/system :backend.web.service/web-service :io.pedestal.http/interceptors (nth 4) class reflect/type-reflect)
  (-> ig-state/system :backend.web.service/web-service :io.pedestal.http/server)
  (-> ig-state/system :backend.web.service/web-service :io.pedestal.http/server deref class)
  (->> ig-state/system :backend.web.service/web-service :io.pedestal.http/server deref
       (satisfies? AlephServer))
  aleph.netty$start_server$reify__30279
  (-> ig-state/system
      :shared.lamport/clock
      deref)
  (-> ig-state/system
      :client.propagate/monitor)
  (->> ig-state/system
       ::sessions/session-atom
       deref)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       class)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       keys)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       first)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       count)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       keys)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth.apps.shared.connection/web-socket)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds
       count)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds
       vals)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds
       vals
       first)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds
       vals
       first
       keys)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds
       vals
       first
       :shared.world/internal-state)
  (->> ig-state/system
       ::sessions/session-atom
       deref
       vals
       first
       :frereth/worlds
       vals
       first
       :shared.world/cookie
       #_vec
       String.)
  (-> ig-state/system
      ::sessions/session-atom
      deref
      (sessions/get-by-state ::sessions/pending))
  (-> ig-state/system
      ::sessions/session-atom
      deref
      (sessions/get-by-state ::sessions/active))
  (-> ig-state/system keys)
  (-> ig-state/system ::weald/state-atom)
  (-> ig-state/system ::weald/state-atom deref ::weald/entries)
  )

(comment
  (source renderer.handlers/handle-forked)

  (let [raw-routes (renderer-handlers/build-routes)
        custom-verbs #{:frereth/forward}
        verbs (set/union @#'table-route/default-verbs
                         custom-verbs)
        processed-routes (table-route/table-routes {:verbs verbs}
                                                   raw-routes)
        router-intc (route/router processed-routes :map-tree)
        router (:enter router-intc)
        request {:path-info "/api/v1/forking"
                 :request-method :post}
        initial-context {:request request}
        routed (router initial-context)]
    routed)
  )
