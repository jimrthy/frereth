(ns tracker.client
  (:require
    [com.fulcrologic.fulcro-css.css-injection :as cssi]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.networking.http-remote :as net]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [taoensso.timbre :as log]
    [tracker.application :refer [SPA]]
    [tracker.model.session :as session-model]
    [tracker.ui.root :as root]
    [tracker.ui.session :as session-ui]))

(defn ^:export refresh []
  (log/info "Hot code Remount")
  (cssi/upsert-css "componentcss" {:component root/Root})
  (app/mount! SPA root/Root "app"))

(defn ^:export init []
  (log/info "Application starting.")
  (cssi/upsert-css "componentcss" {:component root/Root})
  ;(inspect/app-started! SPA)
  (app/set-root! SPA root/Root {:initialize-state? true})
  (dr/initialize! SPA)
  (log/info "Starting session machine.")
  (uism/begin! SPA session-model/session-machine ::session-model/session
    {:actor/login-form      root/Login
     :actor/current-session session-ui/Session})
  (app/mount! SPA root/Root "app" {:initialize-state? false}))


(comment
  (inspect/app-started! SPA)
  (app/mounted? SPA)
  (app/set-root! SPA root/Root {:initialize-state? true})
  (uism/begin! SPA session-model/session-machine ::session/session
               {:actor/login-form root/Login
                :actor/current-session session-ui/Session})

  (reset! (::app/state-atom SPA) {})

  ;; TODO: What does this do?
  (merge/merge-component! my-app Settings {:account/time-zone "America/Los_Angeles"
                                           :account/real-name "Joe Schmoe"})
  (dr/initialize! SPA)
  (app/current-state SPA)
  (dr/change-route SPA ["settings"])
  (app/mount! SPA root/Root "app")
  (comp/get-query root/Root {})
  (comp/get-query root/Root (app/current-state SPA))



  (-> SPA ::app/runtime-atom deref ::app/indexes)
  (comp/class->any SPA root/Root)
  (let [s (app/current-state SPA)]
    (fdn/db->tree [{[:component/id :login] [:ui/open? :ui/error :account/email
                                            {[:root/current-session '_] (comp/get-query session-ui/Session)}
                                            [::uism/asm-id ::session-model/session]]}] {} s)))
