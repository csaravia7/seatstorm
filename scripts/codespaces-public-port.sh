#!/usr/bin/env bash
# Makes forwarded port 8080 PUBLIC in this Codespace (devcontainer.json cannot set visibility).
# Uses the Codespace's own token; retries until the port exists (it appears when something listens on it).
# Usage: scripts/codespaces-public-port.sh [port] [max_wait_seconds]
set -uo pipefail
PORT="${1:-8080}"; MAX="${2:-900}"
[[ -n "${CODESPACE_NAME:-}" ]] || { echo "Not running in a Codespace; nothing to do."; exit 0; }
command -v gh >/dev/null 2>&1 || { echo "gh CLI not found"; exit 1; }
TOKEN="${GITHUB_CODESPACE_TOKEN:-${GITHUB_TOKEN:-}}"
end=$((SECONDS+MAX))
while (( SECONDS < end )); do
  if GITHUB_TOKEN="$TOKEN" gh codespace ports visibility "${PORT}:public" -c "$CODESPACE_NAME" >/dev/null 2>&1; then
    echo "✅ Port $PORT is now public: https://${CODESPACE_NAME}-${PORT}.app.github.dev"
    exit 0
  fi
  sleep 5
done
echo "⚠️  Could not set port $PORT public (org policy?). Do it manually: Ports tab > $PORT > Port Visibility > Public."
exit 1
