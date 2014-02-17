(ns jsk.explorer
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
            [jsk.tree :as tree]
            [jsk.workflow :as workflow]
            [jsk.job :as job]
            [cljs.core.async :as async :refer [<!]]
            [clojure.string :as string]
            [enfocus.core :as ef])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(def ^:private jstree-id "#jstree")
(def ^:private directory-icon "glyphicon glyphicon-folder-open")
(def ^:private job-icon "glyphicon glyphicon-hdd")
(def ^:private wf-icon "glyphicon glyphicon-tasks")

(defn- enable-rename-directory
  ([]
    (let [tree (tree/instance-for-id jstree-id)
          sel (tree/selected-node jstree-id)]
      (enable-rename-directory sel tree)))

  ([sel tree]
     (when sel
       (.edit tree sel))))

             ;; node-opts (clj->js {:type :directory :text text :parent parent-id :id (util/->explorer-element-id dir-id :dir)})
             ;; new-node (.create_node tree sel node-opts)
(defn- make-directory [node]
  (let [parent-id (get node "id")
        parent-dir-id (util/explorer-element-id->id parent-id)
        sel (tree/selected-node jstree-id)]
    (util/log (str "parent id: " parent-id ", parent-dir-id: " parent-dir-id))
    (when sel
      (go
       (let [text (str "Directory " (util/now-ts)) ; need a unique name for creation renamed later
             dir-data {:directory-name text :directory-id -1 :parent-directory-id parent-dir-id}]
             (<! (rfn/save-directory dir-data)))))))

(defn- rm-directory [dir-id]
  (util/log (str "remove directory with id " dir-id))
  (go
   (<! (rfn/rm-directory dir-id))))

(defn- add-job [dir-id]
  (go
   (<! (rfn/new-empty-job dir-id))))

(defn- add-workflow [dir-id]
  (go
   (<! (rfn/new-empty-workflow dir-id))))

(defn- dir-context-menu [node]
  (let [clj-node (js->clj node)
        dir-explorer-id (get clj-node "id")
        dir-id (util/explorer-element-id->id dir-explorer-id)]
    {
     :add-job {:label "Add Job" :action #(add-job dir-id)}
     :add-workflow {:label "Add Workflow" :action #(add-workflow dir-id)}
     :refresh-directory {:label "Refresh Directory" :action #(tree/refresh-node jstree-id dir-explorer-id)}
     :make-directory {:label "Make Directory" :action #(make-directory clj-node) :separator_before true}
     :rename-directory {:label "Rename Directory" :action #(enable-rename-directory)}
     :rm-directory {:label "Delete Directory" :action #(rm-directory dir-id) :separator_before true}}))

(defn- job-context-menu [node]
  (def job-menu-node node)
  (let [job-id (-> (get node "id") util/explorer-element-id->id)]
    {:rm-job {:label "Delete Job" :action #(rfn/rm-node job-id)}}))
  

(defn- workflow-context-menu [node]
  (def wf-menu-node node)
  (let [wf-id (-> (get node "id") util/explorer-element-id->id)]
    {:rm-workflow {:label "Delete Workflow" :action #(rfn/rm-node wf-id)}}))

(defn- pick-context-menu-fn [type]
  ; (util/log (str "type is " type))
  (condp = type
    :directory dir-context-menu
    :job job-context-menu
    :workflow workflow-context-menu))

(defn- make-context-menu [node cb]
  (let [clj-node (js->clj node)
        type (keyword (get clj-node "type"))
        ctx-menu-fn (pick-context-menu-fn type)]
    ; (util/log (str "make-context-menu node is " clj-node))
    (-> clj-node ctx-menu-fn clj->js cb)))

(defn init [] (tree/init make-context-menu))

(defn- rename-directory [dir-id new-name parent-dir-id]
  (go
   (let [dir-data {:directory-id dir-id
                   :directory-name new-name
                   :parent-directory-id parent-dir-id}]
     
     (<! (rfn/save-directory dir-data)))))

(defn- rename-node [e data]
  (let [{:strs [id text parent type]} (-> data .-node js->clj)
        id-int (util/explorer-element-id->id id)
        parent-id-int (util/explorer-element-id->id parent)]
    ;(util/log (str "in rename-node id: " id ", text: " text ", parent: " parent ", type: " type))
    (if (= "directory" type)
      (rename-directory id-int text parent-id-int))))
    

(defn- create-explorer-node [e data]
  (let [{:strs [id text parent]} (-> data .-node js->clj)]
    (util/log (str "in create-node id: " id ", text: " text ", parent: " parent))))

(defn- directory-data->jstree-format [nn parent-id]
  (map (fn [{:keys [element-type element-id element-name num-children]}]
         (condp = (-> element-type string/lower-case keyword) 
           :directory {:id (util/->explorer-element-id element-id :directory)
                       :parent (util/->explorer-element-id parent-id :directory)
                       :text element-name
                       :type :directory
                       :children (> num-children 0)}

           :job {:id (util/->explorer-element-id element-id :job)
                 :parent (util/->explorer-element-id parent-id :directory)
                 :text element-name
                 :type :job
                 :children false}

           :workflow {:id (util/->explorer-element-id element-id :workflow)
                      :parent (util/->explorer-element-id parent-id :directory)
                      :text element-name
                      :type :workflow
                      :children false}
           (throw (str "Unknown element-type: " element-type))))
       nn))




(em/defsnippet explorer-tree :compiled "public/templates/explorer.html" "#explorer" []
  ; empty!
  )


(defn when-node-activated [event sel-node]
  (util/log "when-node-activated called")
  (let [node (-> sel-node .-node)
        node-type (-> node .-type keyword)
        element-id (-> node .-id util/explorer-element-id->id)]

    ;(util/log (str "node-type: " node-type ", element-id: " element-id))

    (condp = node-type
      :directory nil
      :job (job/show-job-details element-id)
      :workflow (workflow/show-workflow-node-details element-id))))


(defn when-node-moved [e data]
  (util/log "when-node-moved called")
  (let [[_ new-directory-id] (-> data .-parent util/explorer-element-id-dissect)
        [_ old-directory-id] (-> data .-old_parent util/explorer-element-id-dissect)
        [element-type element-id] (-> data .-node .-id util/explorer-element-id-dissect)]
    (util/log (str "when-node-moved node: new-directory-id: " new-directory-id ", old-directory-id: " old-directory-id ", element-type: " element-type ", element-id " element-id))

    (when (not= new-directory-id old-directory-id)
      (go
        (<! (rfn/change-directory {:element-id element-id :element-type element-type :new-parent-directory-id new-directory-id}))))))

(defn- ls-elements
  [node cb]
  ;(util/log (str "node is: " (js->clj node)))
  (go
   (let [id-str (-> node .-id)
         id (util/explorer-element-id->id id-str)
         clj-children (<! (rfn/fetch-explorer-directory id))
         jstree-children (directory-data->jstree-format clj-children id)
         children (clj->js jstree-children)]
     (.call cb (js* "this") children))))

(def ^:private init-data {:core {:data ls-elements :check_callback true}
                                 :types {:directory {:icon directory-icon}
                                         :job {:icon job-icon}
                                         :workflow {:icon wf-icon}}
                                 :plugins [:contextmenu :dnd :types :sort :unique]})

(defn show []
  (util/log "in show")
  (util/showcase (explorer-tree))
  (tree/init-tree jstree-id init-data)
  (tree/register-activate-node-handler jstree-id when-node-activated)
  (tree/register-rename-node-handler jstree-id rename-node)
  (tree/register-move-node-handler jstree-id when-node-moved)
  (tree/register-create-node-handler jstree-id create-explorer-node))


(defmulti dispatch :crud-event)


(defn- parent-directory-present? [{:keys [parent-directory-id]}])

;; if the node exists in the tree, rename it
;; if the node-directory-id is different, move it
;; if the node does not exist, but the parent-directory does, then add it

(defn- handle-node [{:keys [node-id node-type-id node-name node-directory-id] :as msg}]
  (util/log (str "handle-node called for " msg))
  (util/log (str "the node-type is " (util/node-type-id->keyword node-type-id)))

  (let [node-type (util/node-type-id->keyword node-type-id)
        node-explorer-id (util/->explorer-element-id node-id node-type)
        node (tree/get-node jstree-id node-explorer-id)]
    (when node
      (let [clj-node (js->clj node)
            {:strs [parent text]} clj-node
            [_ _ current-directory-id] (util/explorer-element-id-dissect parent)]
        (when (not= text node-name)
          (tree/rename-node jstree-id node-explorer-id node-name))
        (when (not= current-directory-id node-directory-id)
          (tree/mv-node jstree-id node node-directory-id))))

    (when-not node
      (let [parent-node-element-id (util/->explorer-element-id node-directory-id :directory)
            node-params {:id node-explorer-id :type node-type :text node-name :parent parent-node-element-id :children false}]
        (util/log (str "create node with " node-params))
        (when (tree/node-exists? jstree-id parent-node-element-id)
          (tree/make-node jstree-id parent-node-element-id node-params))))))

;; if the directory exists in the tree, rename it if reqd
;; if the parent-directory-id is different, move the directory
(defn- handle-directory [{:keys [directory-id directory-name parent-directory-id] :as msg}]
  (util/log (str "handle-directory called for " msg))
  (let [node-explorer-id (util/->explorer-element-id directory-id :directory)
        node (tree/get-node jstree-id node-explorer-id)]
    (when node
      (let [clj-node (js->clj node)
            {:strs [parent text]} clj-node
            [_ _ current-parent-dir-id] (util/explorer-element-id-dissect parent)]
        (when (not= text directory-name)
          (tree/rename-node jstree-id node-explorer-id directory-name))
        (when (not= current-parent-dir-id parent-directory-id)
          (tree/mv-node jstree-id node parent-directory-id))))

    (when-not node
      (let [parent-node-element-id (util/->explorer-element-id parent-directory-id :directory)
            node-params {:id node-explorer-id :type :directory :text directory-name :parent parent-node-element-id :children false}]
        (util/log (str "create directory with " node-params))
        (when (tree/node-exists? jstree-id parent-node-element-id)
          (tree/make-node jstree-id parent-node-element-id node-params))))))


;; if the element-id exists in the tree and the new-parent-directory-id exists
(defn- handle-directory-change [{:keys [element-id element-name element-type new-parent-directory-id] :as msg}]
  (util/log (str "handle-directory-change for " msg))
  (let [explorer-dir-id (util/->explorer-element-id new-parent-directory-id :directory)
        element-exp-id (util/->explorer-element-id element-id element-type)
        target-dir? (tree/node-exists? jstree-id explorer-dir-id)
        target-element? (tree/node-exists? jstree-id element-exp-id)]

    ;; have to create node too if the target dir is present but the target
    ;; element is not

    (when target-element?
      (if target-dir?
        ;; move element-exp-id to explorer-dir-id
        (tree/mv-node jstree-id element-exp-id explorer-dir-id)
        ;; remove the target element since it is not visible anymore
        (tree/rm-node jstree-id element-exp-id)))

    ;; create the node in the target dir since it is present
    (when (and target-dir? (not target-element?))
      (let [node-params {:id element-exp-id :type element-type :text element-name :parent new-parent-directory-id :children false}]
        (tree/make-node jstree-id explorer-dir-id node-params)))))


               

; when the directory the node is in is not visible, do nothing. that
; data will be retrieved as required when that part of the tree is
; loaded through ui interaction
(defmethod dispatch :node-save [{:keys [node-directory-id] :as msg}]
  (let [explorer-dir-id (util/->explorer-element-id node-directory-id :directory)]
    (when (tree/node-exists? jstree-id explorer-dir-id)
      (handle-node msg))))

; dir created/updated
(defmethod dispatch :directory-save [{:keys [parent-directory-id] :as msg}]
  (let [explorer-dir-id (util/->explorer-element-id parent-directory-id :directory)]
    (when (tree/node-exists? jstree-id explorer-dir-id)
      (handle-directory msg))))

(defmethod dispatch :directory-change [{:keys [element-id element-type new-parent-directory-id] :as msg}]
  (let [explorer-dir-id (util/->explorer-element-id new-parent-directory-id :directory)]
    (when (tree/node-exists? jstree-id explorer-dir-id)
      (handle-directory-change msg))))

(defmethod dispatch :element-rm [{:keys [element-id element-type] :as msg}]
  (util/log (str "explorer directory-rm " msg))
  (let [tree-element-id (util/->explorer-element-id element-id element-type)]
    (when (tree/node-exists? jstree-id tree-element-id)
      (tree/rm-node jstree-id tree-element-id))))

(defmethod dispatch :default [msg]
  (util/log (str "explorer unexpected crud-event " msg)))

(defn handle-event [msg]
  (util/log (str "explorer/handle-event called for " msg))
  (dispatch msg))