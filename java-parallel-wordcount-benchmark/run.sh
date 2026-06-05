#!/usr/bin/env bash
set -e
mkdir -p bin results images
javac -cp "lib/jocl-2.0.4.jar" -d bin src/*.java
java -cp "bin:lib/jocl-2.0.4.jar" WordCounterBenchmark "${1:-the}"
