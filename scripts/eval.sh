#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/mvn.sh -B verify
python3 -m unittest discover -s eval/tests -v
python3 -m eval.runner report --runs target/evaluation/fixture-runs.jsonl --output target/evaluation/report.json
printf '%s\n' 'Offline fixture report: target/evaluation/report.json (not model qualification evidence)'
