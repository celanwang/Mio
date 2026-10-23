#!/usr/bin/env bash
# Standalone maintenance process: never starts Spring, agents or provider connections.
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/mvn.sh -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/admin-classpath.txt
exec java -cp "target/classes:$(cat target/admin-classpath.txt)" org.celanwang.mio.ops.MaintenanceMain "$@"
