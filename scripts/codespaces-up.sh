#!/usr/bin/env bash
# One-command deploy on GitHub Codespaces (or any Docker host): build JVM images, start everything, print the URL.
#   ./scripts/codespaces-up.sh            # build + start
#   SKIP_BUILD=1 ./scripts/codespaces-up.sh   # start with images already built
#   TAG=dev ./scripts/codespaces-up.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${TAG:-dev}"; export TAG
[[ -f .env ]] || cp .env.example .env
set -a; source .env; set +a

if docker compose version >/dev/null 2>&1; then DC=(docker compose); else DC=(docker-compose); fi
FILES=(-f compose.yaml); [[ -f compose.local.yaml ]] && FILES+=(-f compose.local.yaml)

if [[ -z "${SKIP_BUILD:-}" ]]; then
  echo "🔨 Building JVM images (tag: $TAG) — first run takes a few minutes..."
  scripts/build-local-images.sh
fi

echo "🚀 Starting infrastructure and services..."
"${DC[@]}" "${FILES[@]}" --profile apps up -d

echo "⏳ Waiting for the platform to be ready (http://localhost:8080)..."
for i in $(seq 1 120); do
  if curl -sf http://localhost:8080/api/stats >/dev/null 2>&1; then ready=1; break; fi
  sleep 3
done
[[ -n "${ready:-}" ]] || { echo "❌ Timed out. Check: ${DC[*]} ${FILES[*]} --profile apps ps | logs"; exit 1; }

if [[ -n "${CODESPACE_NAME:-}" ]]; then
  URL="https://${CODESPACE_NAME}-8080.app.github.dev"
  # Make the audience port public so phones can open it (needs gh; ignore failures)
  scripts/codespaces-public-port.sh 8080 60 || \
    echo "ℹ️  Make port 8080 Public: Ports tab > 8080 > Port Visibility > Public. Change CONSOLE_PIN first."
else
  URL="http://localhost:8080"
fi

echo
echo "✅ SeatStorm is up"
echo "   🎟️  Audience:   $URL"
echo "   🎬 Stage:      $URL/stage"
echo "   🎛️  Console:    $URL/console   (PIN: ${CONSOLE_PIN:-4321})"
echo "   📊 Kafka UI:   port 8085"
command -v qrencode >/dev/null && qrencode -t ANSIUTF8 "$URL" || true
