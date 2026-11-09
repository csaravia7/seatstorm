#!/usr/bin/env bash
# Crea (idempotente) los secretos de SeatStorm en ambos namespaces a partir de .env.
# Nunca escribe valores en disco ni en el repositorio.
#   uso: k8s/scripts/create-secrets.sh [ruta/.env]
# Opcional: DOCKERHUB_USER / DOCKERHUB_TOKEN -> secreto `dockerhub` + imagePullSecrets en la SA default (spec 11.2).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-$ROOT/.env}"
NAMESPACES=(seatstorm-platform seatstorm)

if [ -f "$ENV_FILE" ]; then
  set -a; # shellcheck disable=SC1090
  source "$ENV_FILE"; set +a
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
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "secret seatstorm-secrets -> $ns"

  if [ -n "${DOCKERHUB_USER:-}" ] && [ -n "${DOCKERHUB_TOKEN:-}" ]; then
    kubectl create secret docker-registry dockerhub -n "$ns" \
      --docker-server=https://index.docker.io/v1/ \
      --docker-username="$DOCKERHUB_USER" --docker-password="$DOCKERHUB_TOKEN" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    kubectl patch serviceaccount default -n "$ns" -p '{"imagePullSecrets":[{"name":"dockerhub"}]}' >/dev/null
    echo "secret dockerhub -> $ns (enlazado a la ServiceAccount default)"
  else
    echo "WARN: sin DOCKERHUB_USER/DOCKERHUB_TOKEN no se crea el secreto dockerhub en $ns (riesgo de ImagePullBackOff por limite de descargas)." >&2
  fi
done
