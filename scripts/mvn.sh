#!/usr/bin/env bash
set -euo pipefail
if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi
idea_maven='/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn'
if [[ -x "$idea_maven" ]]; then
  exec "$idea_maven" "$@"
fi
printf '%s\n' 'Maven 3.6.3+ is required (or IntelliJ IDEA bundled Maven on macOS).' >&2
exit 127
