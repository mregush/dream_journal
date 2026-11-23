#!/usr/bin/env bash
# Exercises only a temporary Compose project, a fake token and generated test data.
set -euo pipefail
umask 077
PROJECT_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
workdir=$(mktemp -d)
export ENV_FILE="$workdir/.env.prod"
export COMPOSE_PROJECT_NAME="dream-journal-smoke-$(date +%s)-$$"
export APP_IMAGE=${SMOKE_IMAGE:-dream-journal:local}
export TELEGRAM_BOT_ENABLED=false
export TELEGRAM_BOT_TOKEN=smoke-test-not-a-real-token
export DB_NAME=dream_journal
export DB_USERNAME=dream_journal
export DB_PASSWORD=smoke-test-not-a-production-password
printf 'APP_IMAGE=%s\nCOMPOSE_PROJECT_NAME=%s\n' "$APP_IMAGE" "$COMPOSE_PROJECT_NAME" > "$ENV_FILE"
source "$PROJECT_ROOT/scripts/common.sh"

cleanup() {
  status=$?
  if [[ $status != 0 ]]; then
    "${COMPOSE[@]}" logs --tail=60 app postgres || true
  fi
  "${COMPOSE[@]}" down --volumes --remove-orphans || true
  rm -rf -- "$workdir"
  exit "$status"
}
trap cleanup EXIT

"${COMPOSE[@]}" up -d --wait --wait-timeout 180 app
[[ $("${COMPOSE[@]}" exec -T app id -u) == 10001 ]]
container=$("${COMPOSE[@]}" ps --all --quiet app)
[[ $(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container") == true ]]
[[ $(docker inspect --format '{{.State.Health.Status}}' "$container") == healthy ]]

"${COMPOSE[@]}" exec -T postgres psql -U dream_journal -d dream_journal -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO bot_users (telegram_user_id, chat_id, first_name) VALUES (555555, 555555, 'Smoke test');
INSERT INTO sleep_entries (telegram_user_id, entry_date, time_zone, sleep_quality, duration_range,
    awakenings, bedtime_states, morning_rest)
VALUES (555555, '2026-11-23', 'Europe/Moscow', 8, 'SEVEN_TO_EIGHT', 0, ARRAY['RELAXED'], 9);
SQL
bash "$PROJECT_ROOT/scripts/deploy.sh"
[[ $("${COMPOSE[@]}" exec -T postgres psql -U dream_journal -d dream_journal -Atc \
  'SELECT sleep_quality FROM sleep_entries WHERE telegram_user_id = 555555') == 8 ]]

# Restart the complete stack without deleting its volume and check persisted diary data.
"${COMPOSE[@]}" stop app postgres
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 app
[[ $("${COMPOSE[@]}" exec -T postgres psql -U dream_journal -d dream_journal -Atc \
  'SELECT sleep_quality FROM sleep_entries WHERE telegram_user_id = 555555') == 8 ]]
container=$("${COMPOSE[@]}" ps --all --quiet app)
[[ $(docker inspect --format '{{.State.Health.Status}}' "$container") == healthy ]]
echo 'Docker smoke test passed: startup, migrations, non-root/read-only runtime, update and data persistence.'
