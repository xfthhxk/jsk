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

(load "jsk/repl")
(jsk.repl/start-server)
(jsk.repl/stop-server)

```


## License

Copyright © 2013 Amar Mehta

Distributed under the Eclipse Public License, the same as Clojure.
