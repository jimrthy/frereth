(ns tracker.ui.session
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]))

(defsc Session
  "Session representation. Used primarily for server queries. On-screen representation happens in Login component."
  [this
   {:keys [:account/name
           :player/id
           :session/valid?]
    :as props}]
  {:query         [:account/name :player/id :session/valid?]
   :ident         (fn [] [:component/id :session])
   ;; Q: What does this do?
   :pre-merge     (fn [{:keys [data-tree]}]
                    (merge {:session/valid? false :account/name ""}
                      data-tree))
   :initial-state {:session/valid? false :account/name ""}})

(def ui-session (comp/factory Session))
