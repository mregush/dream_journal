#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

if [[ ${1:-} == --pull && $# == 1 ]]; then
  # Download the new image before stopping the currently running application.
  "${COMPOSE[@]}" pull app
elif [[ $# != 0 ]]; then
  echo "Usage: $0 [--pull]" >&2
  exit 1
fi

"${COMPOSE[@]}" up -d --wait --wait-timeout 120 postgres
"${COMPOSE[@]}" stop app
if ! "${COMPOSE[@]}" up -d --no-deps --wait --wait-timeout 120 app; then
  echo "Application startup failed. Inspect logs with: docker compose --env-file .env.prod -f compose.prod.yaml logs --tail=100 app" >&2
  exit 1
fi
"${COMPOSE[@]}" ps
