#!/bin/bash
# Runs the message-rendering self-check with assertions enabled.
# Usage: scripts/check.sh   (runs mvn test-compile itself)

set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

MVN="${MVN:-mvn}"
"$MVN" -q test-compile
"$MVN" -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

SEP=":"
case "${OS:-}" in Windows_NT) SEP=";" ;; esac

exec java -ea \
  -cp "target/test-classes${SEP}target/classes${SEP}$(cat target/cp.txt)" \
  io.github.kodashas.cosmeticsbridge.RenderCheck
