#!/usr/bin/env bash
# Starts the warm Mode-2 worker (HTTP) in Knarr's Galette-instrumented JDK.
#
# Usage: scripts/run-worker.sh [port] [warm-up request JSON]
#   GET  http://127.0.0.1:<port>/health
#   POST http://127.0.0.1:<port>/analyze   (body: Mode2Worker.Request as JSON)
#
# Requires the Bell stack in /workspace/.m2-bell and Knarr's instrumented JDK
# (see docs/mode2-log.md in the stack repo, section "Reproduce"). Other locations:
# MODE2_REPO (Maven repository), MODE2_JAVA (instrumented java binary).
set -euo pipefail
export JAVA_HOME="${MODE2_JAVA_HOME:-/opt/microsoft-jdk-21.0.10}"   # Mode-2 JDK (21 since 2026-11-09)
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")/.."
PORT="${1:-8099}"
WARMUP="${2:-}"
REPO="${MODE2_REPO:-/workspace/.m2-bell}"
JAVA="${MODE2_JAVA:-/workspace/knarr-bell/knarr/target/galette/java/bin/java}"
AGENT="$REPO/edu/neu/ccs/prl/galette/galette-agent/1.1.0-SNAPSHOT/galette-agent-1.1.0-SNAPSHOT.jar"
PREFIXES="$(sed -n 's:.*<branch.prefixes>\(.*\)</branch.prefixes>.*:\1:p' pom.xml)"

[ -x "$JAVA" ] || { echo "instrumented JDK missing: $JAVA" >&2; exit 1; }
[ -f "$AGENT" ] || { echo "Galette agent missing: $AGENT" >&2; exit 1; }

mvn -B -q -Dmaven.repo.local="$REPO" compile dependency:build-classpath \
    -Dmdep.outputFile=target/worker-classpath.txt -Dmdep.includeScope=runtime
CP="target/classes:$(cat target/worker-classpath.txt)"

exec "$JAVA" -cp "$CP" \
  -Xbootclasspath/a:"$AGENT" -javaagent:"$AGENT" \
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/edu.neu.ccs.prl.galette.internal.transform=ALL-UNNAMED \
  -Xmx4g \
  -Dgalette.symbolic.branchPrefixes="$PREFIXES" \
  -Dgalette.cache="$PWD/target/galette-cache" \
  -Dmode2.plainJava="${PLAIN_JAVA_HOME:-$JAVA_HOME}" \
  uncertaintree.mode2.Mode2Worker "$PORT" ${WARMUP:+"$WARMUP"}
