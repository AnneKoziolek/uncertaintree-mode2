#!/usr/bin/env bash
# Maven for this repository with the Mode-2 JDK (21 since 2026-11-09) and the separate Maven
# repository of the Bell stack, e.g.  scripts/mvn.sh verify   or   scripts/mvn.sh -Dit.test=Mode2EngineITCase#scenarioC verify
# MODE2_JAVA_HOME and MODE2_REPO override the defaults.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${MODE2_JAVA_HOME:-/opt/microsoft-jdk-21.0.10}"
export PATH="$JAVA_HOME/bin:$PATH"
exec mvn -B -Dmaven.repo.local="${MODE2_REPO:-/workspace/.m2-bell}" "$@"
