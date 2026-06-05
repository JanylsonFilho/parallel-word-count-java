#!/usr/bin/env bash
set -e
mkdir -p bin
javac -cp "lib/jocl-2.0.4.jar" -d bin src/*.java
