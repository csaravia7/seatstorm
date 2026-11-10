#!/usr/bin/env bash
# Crea (idempotente) los secretos de SeatStorm en ambos namespaces a partir de .env.
# Nunca escribe valores en disco ni en el repositorio.
#   uso: k8s/scripts/create-secrets.sh [ruta/.env]
# DOCKER_ID / DOCKER_PAT -> secreto `dockerhub` + imagePullSecrets en la SA default (spec 11.2).
# (el PAT nunca se imprime, no va en argumentos ni se escribe a disco)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-$ROOT/.env}"
NAMESPACES=(seatstorm-platform seatstorm)

if [ -f "$ENV_FILE" ]; then
  # Las variables ya exportadas (p. ej. por setup-env.sh) tienen prioridad sobre .env
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue;; esac
    [[ "$k" =~ ^[A-Z0-9_]+$ ]] || continue
    [ -z "${!k:-}" ] && export "$k=$v"
  done < "$ENV_FILE"
else
  echo "WARN: $ENV_FILE no existe; se usan variables del entorno o valores por defecto de compose." >&2
fi

DB_PASSWORD="${DB_PASSWORD:-seatstorm}"
ADMIN_TOKEN="${ADMIN_TOKEN:-change-me-admin}"
SESSION_SECRET="${SESSION_SECRET:-change-me-32-bytes-minimum-secret}"
CONSOLE_PIN="${CONSOLE_PIN:-4321}"
TICKET_SECRET="${TICKET_SECRET:-change-me-ticket-secret}"
ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-}"
NVIDIA_API_KEY="${NVIDIA_API_KEY:-}"
# Llega a assistant-agent por envFrom (el deployment ya no lo fija); setup-env.sh lo pone en nvidia si solo hay NVIDIA_API_KEY.
LLM_PROVIDER="${LLM_PROVIDER:-anthropic}"
# Arize AX (opcional): si estan vacios, assistant-agent no exporta trazas. Llegan por envFrom.
ARIZE_SPACE_ID="${ARIZE_SPACE_ID:-}"
ARIZE_API_KEY="${ARIZE_API_KEY:-}"
ARIZE_OTLP_ENDPOINT="${ARIZE_OTLP_ENDPOINT:-https://otlp.arize.com/v1}"
ARIZE_PROJECT_NAME="${ARIZE_PROJECT_NAME:-seatstorm-assistant}"

for v in DB_PASSWORD ADMIN_TOKEN SESSION_SECRET TICKET_SECRET; do
  case "${!v}" in change-me*|seatstorm) echo "WARN: $v tiene el valor por defecto; cambialo para un AKS accesible desde Internet." >&2 ;; esac
done

for ns in "${NAMESPACES[@]}"; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

  kubectl create secret generic seatstorm-secrets -n "$ns" \
    --from-literal=DB_PASSWORD="$DB_PASSWORD" \
    --from-literal=ADMIN_TOKEN="$ADMIN_TOKEN" \
    --from-literal=SESSION_SECRET="$SESSION_SECRET" \
    --from-literal=CONSOLE_PIN="$CONSOLE_PIN" \
    --from-literal=TICKET_SECRET="$TICKET_SECRET" \
    --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
    --from-literal=NVIDIA_API_KEY="$NVIDIA_API_KEY" \
    --from-literal=LLM_PROVIDER="$LLM_PROVIDER" \
    --from-literal=ARIZE_SPACE_ID="$ARIZE_SPACE_ID" \
    --from-literal=ARIZE_API_KEY="$ARIZE_API_KEY" \
    --from-literal=ARIZE_OTLP_ENDPOINT="$ARIZE_OTLP_ENDPOINT" \
    --from-literal=ARIZE_PROJECT_NAME="$ARIZE_PROJECT_NAME" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "secret seatstorm-secrets -> $ns"

  if [ -n "${DOCKER_ID:-}" ] && [ -n "${DOCKER_PAT:-}" ]; then
    # El PAT viaja por stdin (no en la linea de comandos).
    auth="$(printf '%s:%s' "$DOCKER_ID" "$DOCKER_PAT" | base64 | tr -d '\n')"
    printf '{"auths":{"https://index.docker.io/v1/":{"username":"%s","password":"%s","auth":"%s"}}}' \
        "$DOCKER_ID" "$DOCKER_PAT" "$auth" |
      kubectl create secret generic dockerhub -n "$ns" --type=kubernetes.io/dockerconfigjson \
        --from-file=.dockerconfigjson=/dev/stdin --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    kubectl patch serviceaccount default -n "$ns" -p '{"imagePullSecrets":[{"name":"dockerhub"}]}' >/dev/null
    echo "secret dockerhub -> $ns (enlazado a la ServiceAccount default)"
  else
    echo "WARN: sin DOCKER_ID/DOCKER_PAT no se crea el secreto dockerhub en $ns (ImagePullBackOff si tu repositorio es privado; limite de descargas anonimas si es publico)." >&2
  fi
done
