;;;;;;;;;;;;;;;;;
;; Stolen from David Nolen
;; https://github.com/swannodette/swannodette.github.com/blob/master/code/blog/src/blog/autocomplete/core.cljs
;;;;;;;;;;;;;;;;;
(ns web.suggest
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:require
     [goog.userAgent :as ua]
     [goog.events :as events]
     [goog.events.EventType]
     [clojure.string :as string]
     [cljs.core.async :refer [>! <! alts! chan sliding-buffer put!]] ))

;-----------------------------------------------------------------------
;  Interface representation protocols
;-----------------------------------------------------------------------
(defprotocol IHideable
  (-hide! [view])
  (-show! [view]))

(defprotocol ITextField
  (-set-text! [field txt])
  (-text [field]))

(defprotocol IUIList
  (-set-items! [list items]))


;-----------------------------------------------------------------------
;  Google suggest like functions without any HTML concerns
;-----------------------------------------------------------------------
