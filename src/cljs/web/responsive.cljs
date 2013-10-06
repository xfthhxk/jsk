(ns web.responsive
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:require
    [cljs.core.async :refer [>! <! alts! chan]]
    [web.dom :as dom]))

;-----------------------------------------------------------------------
; Declarations
;-----------------------------------------------------------------------

(def ENTER 13)
(def UP_ARROW 38)
(def DOWN_ARROW 40)
(def TAB 9)
(def ESC 27)


(def KEYS #{UP_ARROW DOWN_ARROW ENTER TAB ESC})


(defn key-event->keycode [e]
  (.-keyCode e))

(defn key->keyword [code]
  (condp = code
    UP_ARROW   :previous
    DOWN_ARROW :next
    ENTER      :select
    TAB        :select
    ESC        :exit))



;-----------------------------------------------------------------------
; Interface representation protocols
;-----------------------------------------------------------------------
(defprotocol IHighlightable
  (-highlight! [list n])
  (-unhighlight! [list n]))
