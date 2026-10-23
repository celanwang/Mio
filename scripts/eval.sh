#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mio_eval_run_id=$(python3 -m eval.acceptance begin)
mio_eval_fingerprint=$(python3 -c 'import json; print(json.load(open("target/evaluation/acceptance-run.json"))["source_fingerprint"])')
mio_eval_revision=$(python3 -c 'import json; print(json.load(open("target/evaluation/acceptance-run.json"))["source_revision"])')
./scripts/mvn.sh -B -Dmio.eval.run-id="$mio_eval_run_id" -Dmio.source-fingerprint="$mio_eval_fingerprint" -Dmio.source-revision="$mio_eval_revision" verify
python3 -m eval.release
node --check src/main/resources/static/goals.js
python3 -m eval.acceptance python
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

if [[ -n "${MIO_TEST_PG_URL:-}" ]]; then
  python3 -m eval.acceptance report --require-postgres
else
  python3 -m eval.acceptance report
fi
printf '%s\n' 'Multi-Agent acceptance: target/evaluation/acceptance.md (controlled preview only)'
