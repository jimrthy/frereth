(ns user
  ;; byte-streams.graph is failing with a syntax error at line 108
  ;; because there's no such var, s/->source.
  ;; Where s is the manifold.stream ns.
  ;; I can require that with no problem.
  ;; Trying to load byte-streams fails because of the failure
  ;; in byte-streams.graph.
  ;; This is...odd.
  (:require [aleph.http.server :as http-server]
            [aleph.udp :as udp]
            [backend.main :as main]
            [backend.web.routes :as routes]
            [byte-streams :as b-s]
            [cider.piggieback]
            [client.registrar :as registrar]
            [cljs.repl.browser :as cljs-browser]
            [clojure.core.async :as async]
            [clojure
             [data :as data]
             [edn :as edn]
             [pprint :refer (pprint)]
             [reflect :as reflect]
             [repl :refer (apropos dir doc pst root-cause source)]]
            [clojure.java.io :as io]
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
            [manifold
             [deferred :as dfrd]
             [stream :as strm]]
            [integrant.core :as ig]
            [renderer.lib :as renderer]
            [client.networking :as client-net]
            [frereth.cp.client.state :as client-state]
            [renderer.sessions :as sessions]
            [backend.web.routes :as routes]))

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
  (.close @sample-port)

  ;;; To actually put that to use and create a server
  (def server-cfg (build-server-cfg ig-state/system))
  server-cfg
  (keys server-cfg)
  (def server (build-server (assoc server-cfg
                                   :backend.system/socket {:backend.system/server-port server-port})))
  server
  (keys server)
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
  ;; instructions about writing and index.html.

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

(println "Run (setup-monitor! {::routes/handler-map {::routes/debug? true}}) and then (go) to start the Monitor")

(comment
  (setup-monitor! {:backend.system/routes {::routes/debug? true}})
  (go)

  (-> ig-state/system
      keys)
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
  )

(comment
  (def my-server (http-server/start-server (fn [request]
                                             {:status 200
                                              :body (with-out-str (pprint request))})
                                           {:host "127.0.0.1"
                                            :port 10556}))
  my-server
  (let [reflection (reflect/reflect my-server)]
    (comment
      (keys reflection)
      (:flags reflection)
      (:base reflection))
    (->> reflection
         :members
         (map :name)))
  (.close my-server)
  (.start my-server)
  )
