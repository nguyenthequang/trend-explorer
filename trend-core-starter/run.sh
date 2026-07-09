#!/usr/bin/env bash
# Compile everything and run the zero-dependency test harness.
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -d out $(find src -name '*.java')
java -cp out com.quang.trend.core.CoreTests
