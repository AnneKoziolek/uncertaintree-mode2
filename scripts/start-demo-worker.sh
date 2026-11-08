#!/usr/bin/env bash
# One command for the demo: prepares a CPS1 scenario, starts the warm Mode-2 worker and
# warms it up with that scenario (about 60 s), so the first real request is warm (about 6 s).
#
# Usage: scripts/start-demo-worker.sh [scenario A|B] [port]
# Afterwards, from another shell:
#   curl -s http://127.0.0.1:<port>/health
#   curl -s -H 'Content-Type: application/json' --data @target/demo-request-<scenario>.json \
#        http://127.0.0.1:<port>/analyze
#
# Requires the Bell stack in /workspace/.m2-bell and Knarr's instrumented JDK; post-create
# builds both (see the stack's docs/mode2-log.md, section "Reproduce").
# Other locations: MODE2_REPO (Maven repository), MODE2_JAVA (as in run-worker.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
SCENARIO="${1:-A}"
PORT="${2:-8099}"
REPO="${MODE2_REPO:-/workspace/.m2-bell}"
REQUEST="target/demo-request-$SCENARIO.json"

mvn -B -q -Dmaven.repo.local="$REPO" test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=test
java -cp "target/test-classes:target/classes:$(cat target/demo-classpath.txt)" \
    uncertaintree.mode2.PrepareDemoRequest "$SCENARIO" "$REQUEST" 9420

echo "Starting worker on port $PORT with warm-up $REQUEST ..."
exec scripts/run-worker.sh "$PORT" "$REQUEST"
