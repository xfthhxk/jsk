(ns jsk.tree
 "jstree interface"
  (:require [jsk.util :as ju]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:use [jayq.core :only [$]]))
  

(defn- register-event-handler [tree-id event-name handler]
  (-> tree-id $ (.on event-name handler)))

(defn init [context-menu-fn]
  (set! (-> js/window .-$ .-jstree .-defaults .-contextmenu .-items) context-menu-fn))

(defn init-tree [tree-id init-data]
  (-> tree-id $ (.jstree (clj->js init-data))))

(defn register-activate-node-handler [tree-id handler]
  (register-event-handler tree-id "activate_node.jstree" handler))

(defn register-rename-node-handler [tree-id handler]
  (register-event-handler tree-id "rename_node.jstree" handler))

(defn register-create-node-handler [tree-id handler]
  (register-event-handler tree-id "create_node.jstree" handler))


(defn instance-for-id [tree-id]
  (-> tree-id $ (.jstree true)))

;; gets the selected node in the tree if any otherwise nil
(defn selected-node [tree-id]
  (-> tree-id instance-for-id .get_selected first))

(defn get-node
  "tree-id and node-id are the html ids (string).
   Returns the node or nil"
  [tree-id node-id]
  ; .get_node returns false if the node-id is not known
  (if-let [ans (-> tree-id instance-for-id (.get_node node-id))]
    ans))

(defn node-exists? [tree-id node-id]
  (if (get-node tree-id node-id) true false))
    
(defn rename-node [tree-id node-id new-node-name]
  (when (get-node tree-id node-id)
    (-> tree-id instance-for-id (.rename_node node-id new-node-name))))

(defn rm-node [tree-id node-id]
  (-> tree-id instance-for-id (.delete_node node-id)))

(defn mv-node [tree-id node-id parent-node-id]
  (-> tree-id instance-for-id (.move_node node-id parent-node-id)))

(defn create-node
  "Creates a new node under parent-node-id in tree with id tree-id.
   node is a map"
  [tree-id parent-node-id node]
  (-> tree-id instance-for-id (.create_node parent-node-id (clj->js node))))
