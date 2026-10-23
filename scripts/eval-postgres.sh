#!/usr/bin/env bash
# Disposable PostgreSQL, loopback only, no business credentials or persistent volume.
set -euo pipefail
cd "$(dirname "$0")/.."
mio_eval_container=$(docker run --rm -d --label mio.evaluation=true -e POSTGRES_PASSWORD=mio-eval-only -p 127.0.0.1::5432 pgvector/pgvector:pg16)
trap 'docker rm -f "$mio_eval_container" >/dev/null 2>&1 || true' EXIT
mio_eval_ready=false
for mio_eval_attempt in {1..30}; do
  if docker exec "$mio_eval_container" pg_isready -U postgres >/dev/null 2>&1; then mio_eval_ready=true; break; fi
  sleep 1
done
if [[ "$mio_eval_ready" != true ]]; then printf '%s\n' 'PostgreSQL did not become ready' >&2; exit 1; fi
mio_eval_binding=$(docker port "$mio_eval_container" 5432)
export MIO_TEST_PG_URL="jdbc:postgresql://$mio_eval_binding/postgres"
export MIO_TEST_PG_USERNAME=postgres
export MIO_TEST_PG_PASSWORD=mio-eval-only
export MIO_TEST_PG_CONTAINER="$mio_eval_container"
if [[ "$#" -gt 0 ]]; then ./scripts/mvn.sh "$@"; else ./scripts/eval.sh; fi
