(ns jsk.explorer
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
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

(def ^:private root-parent-dir-id -1)

(defn- ->element-id [id node-type]
  (if (= root-parent-dir-id id)
    "#"
   (str "exp-" (name node-type) "-" id)))

(defn- element-id->id [elem-id]
  (if (= "#" elem-id)
    root-parent-dir-id
    (-> (string/split elem-id #"-") last util/str->int)))

(defn- jstree-instance []
  (-> jstree-id $ (.jstree true)))

(defn- action-fn [data]
  (util/log (str "in action fn: " data)))


;;----------------------------------------------------------------------
;; Job node context menu and fns
;;----------------------------------------------------------------------
(defn job-context-menu [node]

  )

;;----------------------------------------------------------------------
;; Workflow node context menu and fns
;;----------------------------------------------------------------------
(defn wf-context-menu [node]
  )


;;----------------------------------------------------------------------
;; Directory node context menu and fns
;;----------------------------------------------------------------------

;; gets the selected node in the tree if any otherwise nil
(defn- selected-node [tree]
  (-> tree .get_selected first))

(defn- enable-rename-directory
  ([]
    (let [tree (jstree-instance)
          sel (selected-node tree)]
      (enable-rename-directory sel tree)))

  ([sel tree]
     (when sel
       (.edit tree sel))))

(defn- make-directory [node]
  (let [parent-id (:id node)
        parent-dir-id (element-id->id parent-id)
        tree (jstree-instance)
        sel (selected-node tree)]
    (util/log (str "parent id: " parent-id ", parent-dir-id: " parent-dir-id))
    (when sel
      (go 
       (let [text (str "Directory " (util/now-ts)) ; need a unique name for creation renamed later
             dir-data {:directory-name text :directory-id -1 :parent-directory-id parent-dir-id}
             dir-id (<! (rfn/save-directory dir-data))
             node-opts (clj->js {:type :directory :text text :parent parent-id :id (->element-id dir-id :dir)})
             new-node (.create_node tree sel node-opts)]
         (enable-rename-directory new-node tree))))))

(defn- rm-directory [dir-id]
  (util/log (str "remove directory with id " dir-id))
  (go
   (<! (rfn/rm-directory dir-id))))

(defn- rm-job [])
(defn- rm-workflow [])

(defn add-job [dir-id]
  (go
   (<! (rfn/new-empty-job dir-id))))

(defn add-workflow [dir-id]
  (go
   (<! (rfn/new-empty-workflow dir-id))))

(defn dir-context-menu [node]
  (let [clj-node (js->clj node)
        dir-id (-> (get clj-node "id") element-id->id)]
    {
     :add-job {:label "Add Job" :action #(add-job dir-id)}
     :add-workflow {:label "Add Workflow" :action #(add-workflow dir-id)}
     :make-directory {:label "Make Directory" :action #(make-directory node) :separator_before true}
     :rename-directory {:label "Rename Directory" :action #(enable-rename-directory)}
     :rm-directory {:label "Delete Directory" :action #(rm-directory dir-id) :separator_before true}}))

(defn job-context-menu [node]
  {:rm-job {:label "Delete Job" :action #(rm-job)}})

(defn workflow-context-menu [node]
  {:rm-workflow {:label "Delete Workflow" :action #(rm-workflow)}})

(defn- pick-context-menu-fn [type]
  (util/log (str "type is " type))
  (condp = type
    :directory dir-context-menu
    :job job-context-menu
    :workflow workflow-context-menu))

(defn- make-context-menu [node cb]
  (let [clj-node (js->clj node)
        type (keyword (get clj-node "type"))
        ctx-menu-fn (pick-context-menu-fn type)]
    (util/log (str "make-context-menu node is " clj-node))
    (-> clj-node ctx-menu-fn clj->js cb)))

(defn- rename-directory [dir-id new-name parent-dir-id]
  (go
   (let [dir-data {:directory-id dir-id
                   :directory-name new-name
                   :parent-directory-id parent-dir-id}]
     
     (<! (rfn/save-directory dir-data)))))

(defn- rename-node [e data]
  (let [{:strs [id text parent type]} (-> data .-node js->clj)
        id-int (element-id->id id)
        parent-id-int (element-id->id parent)]
    (util/log (str "in rename-node id: " id ", text: " text ", parent: " parent ", type: " type))
    (if (= "directory" type)
      (rename-directory id-int text parent-id-int))))
    

(defn- create-explorer-node [e data]
  (let [{:strs [id text parent]} (-> data .-node js->clj)]
    (util/log (str "in create-node id: " id ", text: " text ", parent: " parent))))

(defn- directory-data->jstree-format [nn parent-id]
  (map (fn [{:keys [element-type element-id element-name num-children]}]
         (condp = (-> element-type string/lower-case keyword) 
           :directory {:id (->element-id element-id :dir)
                       :parent (->element-id parent-id :dir)
                       :text element-name
                       :type :directory
                       :children (> num-children 0)}

           :job {:id (->element-id element-id :job)
                 :parent (->element-id parent-id :dir)
                 :text element-name
                 :type :job
                 :children false}

           :workflow {:id (->element-id element-id :wf)
                      :parent (->element-id parent-id :dir)
                      :text element-name
                      :type :workflow
                      :children false}
           (throw (str "Unknown element-type: " element-type))))
       nn))


(defn- ls-elements
  [node cb]
  (util/log (str "node is: " (js->clj node)))
  (go
   (let [id-str (-> node .-id)
         id (element-id->id id-str)
         clj-children (<! (rfn/fetch-explorer-directory id))
         jstree-children (directory-data->jstree-format clj-children id)
         children (clj->js jstree-children)]
     (.call cb (js* "this") children))))

(def ^:private init-data (clj->js {:core {:data ls-elements :check_callback true}
                                   :types {:directory {:icon directory-icon}
                                           :job {:icon job-icon}
                                           :workflow {:icon wf-icon}}
                                   :plugins [:contextmenu :dnd :types]}))



(defn init []
  (set! (-> js/window .-$ .-jstree .-defaults .-contextmenu .-items) make-context-menu))

(em/defsnippet explorer-tree :compiled "public/templates/explorer.html" "#explorer" []
  ; empty!
  )


(defn show-job-details [job-id]
  )

(defn when-node-activated [event sel-node]
  (util/log "when-node-activated called")
  (let [node (-> sel-node .-node)
        node-type (-> node .-type keyword)
        element-id (-> node .-id element-id->id)]

    (util/log (str "node-type: " node-type ", element-id: " element-id))

    (condp = node-type
      :directory nil
      :job (show-job-details element-id)
      :workflow (workflow/show-workflow-node-details element-id))))

(defn show []
  (util/showcase (explorer-tree))
  (-> jstree-id $ (.jstree init-data))
  (-> jstree-id $ (.on "activate_node.jstree" when-node-activated))
  (-> jstree-id $ (.on "rename_node.jstree" rename-node))
  (-> jstree-id $ (.on "create_node.jstree" create-explorer-node)))

