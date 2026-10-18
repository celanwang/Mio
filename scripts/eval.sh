#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/mvn.sh -B verify
python3 -m unittest discover -s eval/tests -v
python3 -m eval.runner report --runs target/evaluation/fixture-runs.jsonl --output target/evaluation/report.json
python3 -m eval.runner injections --runs target/evaluation/injection-fixtures.jsonl --output target/evaluation/injection-report.json
python3 -m eval.runner coverage --output target/evaluation/coverage.json
python3 -m eval.runner events --runs target/evaluation/fixture-runs.jsonl --output target/evaluation/fixture-events.json
python3 -m eval.runner shadow --events target/evaluation/fixture-events.json --output target/evaluation/fixture-shadow.json
if python3 -m eval.runner promotion --report target/evaluation/report.json --injections target/evaluation/injection-report.json --shadow target/evaluation/fixture-shadow.json --output target/evaluation/promotion.json; then
  printf '%s\n' 'ERROR: scripted fixtures must never qualify for promotion' >&2
  exit 1
else
  eval_gate_status=$?
  if [[ "$eval_gate_status" -ne 1 ]]; then exit "$eval_gate_status"; fi
fi
python3 -c 'from eval.summary import write_summary; write_summary("target/evaluation")'
printf '%s\n' 'Offline fixture report: target/evaluation/summary.md (not model qualification evidence)'
