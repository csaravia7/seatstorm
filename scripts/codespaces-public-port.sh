#!/usr/bin/env bash
# Makes forwarded port 8080 PUBLIC in this Codespace (devcontainer.json cannot set visibility).
# Uses the Codespace's own token; retries until the port exists (it appears when something listens on it).
# Usage: scripts/codespaces-public-port.sh [port] [max_wait_seconds]
set -uo pipefail
PORT="${1:-8080}"; MAX="${2:-900}"
[[ -n "${CODESPACE_NAME:-}" ]] || { echo "Not running in a Codespace; nothing to do."; exit 0; }
if ! command -v gh >/dev/null 2>&1; then
  echo ">> gh CLI not found; installing..."
  if command -v apt-get >/dev/null 2>&1 && { sudo apt-get update -qq && sudo apt-get install -y -qq gh; } >/dev/null 2>&1 && command -v gh >/dev/null 2>&1; then
    :
  else
    # Fallback: official release tarball into ~/.local/bin (no root needed)
    case "$(uname -m)" in x86_64|amd64) A=amd64;; aarch64|arm64) A=arm64;; *) echo "unsupported arch"; exit 1;; esac
    url=$(curl -fsSL https://api.github.com/repos/cli/cli/releases/latest | grep -o "https://[^\"]*linux_${A}\.tar\.gz" | head -1)
    [[ -n "$url" ]] || { echo "Could not find a gh release; install gh manually."; exit 1; }
    mkdir -p "$HOME/.local/bin" /tmp/gh-dl && curl -fsSL "$url" | tar -xz -C /tmp/gh-dl --strip-components=1 && cp /tmp/gh-dl/bin/gh "$HOME/.local/bin/gh"
    export PATH="$HOME/.local/bin:$PATH"
  fi
  command -v gh >/dev/null 2>&1 || { echo "gh installation failed"; exit 1; }
fi
# Token with the `codespace` scope. Order: CODESPACES_PAT env var (e.g. a Codespaces user secret),
# interactive prompt (hidden input, never written to disk), then the Codespace's own token as a last resort.
TOKEN="${CODESPACES_PAT:-}"
if [[ -z "$TOKEN" && -t 0 && -t 1 ]]; then
  echo "🔑 A GitHub token with the 'codespace' scope is needed to make port $PORT public."
  echo "   Create one at https://github.com/settings/tokens/new?scopes=codespace&description=seatstorm-ports"
  read -rsp "   Paste the token (input hidden; press Enter to skip and try the built-in token): " TOKEN; echo
fi
if [[ -z "$TOKEN" ]]; then
  TOKEN="${GITHUB_CODESPACE_TOKEN:-${GITHUB_TOKEN:-}}"
  echo "ℹ️  No token provided: using the Codespace's built-in token (may lack permission)."
fi
trap 'unset TOKEN' EXIT
end=$((SECONDS+MAX)); last=""
while (( SECONDS < end )); do
  if out=$(env -u GH_TOKEN GITHUB_TOKEN="$TOKEN" gh codespace ports visibility "${PORT}:public" -c "$CODESPACE_NAME" 2>&1); then
    echo "✅ Port $PORT is now public: https://${CODESPACE_NAME}-${PORT}.app.github.dev"
    exit 0
  fi
  [[ "$out" != "$last" ]] && { echo "gh said: $out"; last="$out"; }
  sleep 5
done
echo "⚠️  Could not set port $PORT public. Last error above. Manual fallback: Ports tab > $PORT > Port Visibility > Public."
exit 1
