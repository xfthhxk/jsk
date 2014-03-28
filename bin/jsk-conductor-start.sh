#!/bin/bash

DB_JAR=./lib/h2-1.3.174.jar 
DB_JAR=./lib/mysql-connector-java-5.1.28-bin.jar 

EXE_JAR=target/jsk-0.1.0-SNAPSHOT-standalone.jar 
CLASSPATH=${DB_JAR}:${EXE_JAR}
EXE=jsk.core

java -cp ${CLASSPATH} ${EXE} --mode conductor --hostname localhost --cmd-port 9000 --status-port 9001 --nrepl-port 7001 &
