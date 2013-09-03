# Job Scheduling Kit
Quartz backed job scheduling.
Provides UI.

## Usage

```shell

mkdir log
touch log/jsk.log
tail -f log/jsk.log

```

* In LightTable connect to the project by CMD+Enter in jsk/repl.clj
* Open an instarepl

```clojure

(load "jsk/repl")       ; loads jsk.repl namespace which exposes start-server and stop-server fns
(jsk.repl/start-server) ; starts the server
(jsk.repl/stop-server)  ; stops the server

```


## License

Copyright © 2013 Amar Mehta

Distributed under the Eclipse Public License, the same as Clojure.
