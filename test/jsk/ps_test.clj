(ns jsk.ps-test
  (:use midje.sweet)
  (:require [jsk.ps :as ps]))


(fact "execute a process without any arguments"
      (ps/exec "echo") => {:output "\n" :exit-code 0})


(fact "execute a process with one argument"
      (ps/exec "echo" [["hello"]]) => {:output "hello\n" :exit-code 0})


(fact "execute cal with two args"
      (let [jan-2012-str "    January 2012\nSu Mo Tu We Th Fr Sa\n 1  2  3  4  5  6  7\n 8  9 10 11 12 13 14\n15 16 17 18 19 20 21\n22 23 24 25 26 27 28\n29 30 31\n\n"]
        (ps/exec "cal" [[1] [2012]]) => {:output jan-2012-str :exit-code 0}))



