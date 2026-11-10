#!/usr/bin/env bash
# Runs once when the Codespace is created: tools, .env with random secrets, Docker Hub login.
set -euo pipefail
cd "$(dirname "$0")/.."

echo ">> Installing helper tools (qrencode, psql)..."
if command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update -qq && sudo apt-get install -y -qq qrencode postgresql-client >/dev/null
elif command -v apk >/dev/null 2>&1; then
  sudo apk add --no-cache libqrencode-tools postgresql-client bash curl >/dev/null || true
fi

if [[ ! -f .env ]]; then
  echo ">> Creating .env with random secrets..."
  cp .env.example .env
  rnd() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32; }
  sed -i "s|^ADMIN_TOKEN=.*|ADMIN_TOKEN=$(rnd)|; s|^SESSION_SECRET=.*|SESSION_SECRET=$(rnd)$(rnd)|; s|^TICKET_SECRET=.*|TICKET_SECRET=$(rnd)|" .env
fi
# Codespaces secrets (ANTHROPIC_API_KEY, NVIDIA_API_KEY) are injected as env vars; copy them into .env
for k in ANTHROPIC_API_KEY NVIDIA_API_KEY; do
  if [[ -n "${!k:-}" ]]; then sed -i "s|^$k=.*|$k=${!k}|" .env; fi
done
[[ -n "${NVIDIA_API_KEY:-}" && -z "${ANTHROPIC_API_KEY:-}" ]] && sed -i "s|^LLM_PROVIDER=.*|LLM_PROVIDER=nvidia|" .env || true

if [[ -n "${DOCKER_ID:-}" ]]; then
  sed -i "s|^DOCKER_ID=.*|DOCKER_ID=${DOCKER_ID}|" .env
  if [[ -n "${DOCKER_PAT:-}" ]]; then
    printf '%s' "$DOCKER_PAT" | docker login -u "$DOCKER_ID" --password-stdin >/dev/null || true
  fi
fi

chmod +x scripts/*.sh
echo ">> Ready. Run:  make codespaces-up   (or ./scripts/codespaces-up.sh)"
