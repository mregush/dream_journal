#!/usr/bin/env bash
# Shared by deployment tools; this file is sourced, not executed directly.
set -euo pipefail
umask 077

PROJECT_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${ENV_FILE:-"$PROJECT_ROOT/.env.prod"}

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy .env.prod.example and configure it first." >&2
  exit 1
fi
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$PROJECT_ROOT/compose.prod.yaml")
"${COMPOSE[@]}" config --quiet
