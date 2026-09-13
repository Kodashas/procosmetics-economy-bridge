#!/bin/bash
# Runs the self-checks with assertions enabled.
# Usage: scripts/check.sh   (runs mvn test-compile itself)

set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

MVN="${MVN:-mvn}"
"$MVN" -q test-compile
"$MVN" -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

SEP=":"
case "${OS:-}" in Windows_NT) SEP=";" ;; esac

CP="target/test-classes${SEP}target/classes${SEP}$(cat target/cp.txt)"

for check in RenderCheck EconomyCheck; do
  java -ea -cp "$CP" "io.github.kodashas.cosmeticsbridge.$check"
done
