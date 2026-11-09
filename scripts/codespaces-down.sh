#!/usr/bin/env bash
# Stop everything. Add --purge to also delete volumes (Kafka/Postgres data).
set -euo pipefail
cd "$(dirname "$0")/.."
if docker compose version >/dev/null 2>&1; then DC=(docker compose); else DC=(docker-compose); fi
FILES=(-f compose.yaml); [[ -f compose.local.yaml ]] && FILES+=(-f compose.local.yaml)
"${DC[@]}" "${FILES[@]}" --profile apps down ${1:+-v}
