#!/usr/bin/env bash
# Builds the 11 images as JVM images and pushes them to your personal Docker Hub account.
#   DOCKER_ID=myuser DOCKER_PAT=... TAG=abc123 scripts/push-dockerhub.sh      (or: make push-images TAG=abc123)
# Requires DOCKER_ID (Docker Hub username) and DOCKER_PAT (personal access token with write access).
# Missing values are prompted for (PAT hidden) when stdin is a terminal; otherwise the script fails.
# The PAT is only sent to `docker login --password-stdin`; it is never placed in arguments or printed.
#   PLATFORMS   target architecture, default linux/amd64 (AKS x86 nodes), a single platform. Use linux/arm64 for arm nodes.
#   DRY_RUN=1   build only: no login, no push (validates the whole build)
# Then deploy with the aks-jvm overlay:  OVERLAY=aks-jvm make k8s-deploy TAG=abc123
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${TAG:-latest}"   # default latest; set TAG=<version> for a specific one
PLATFORMS="${PLATFORMS:-linux/amd64}"
DRY_RUN="${DRY_RUN:-}"
SVCS=(event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification
      assistant-agent bff bot-swarm web)

case "$PLATFORMS" in *,*) echo "ERROR: JVM images support a single platform (PLATFORMS=$PLATFORMS)" >&2; exit 2;; esac

if [ -n "$DRY_RUN" ]; then
  DOCKER_ID="${DOCKER_ID:-seatstorm}"
  echo ">> DRY_RUN: building only, nothing is pushed"
else
  if [ -z "${DOCKER_ID:-}" ]; then
    [ -t 0 ] || { echo "ERROR: DOCKER_ID is required (your Docker Hub username)" >&2; exit 2; }
    read -rp "DOCKER_ID (Docker Hub username): " DOCKER_ID
  fi
  if [ -z "${DOCKER_PAT:-}" ]; then
    [ -t 0 ] || { echo "ERROR: DOCKER_PAT is required (Docker Hub personal access token)" >&2; exit 2; }
    read -rsp "DOCKER_PAT (hidden): " DOCKER_PAT; echo
  fi
  [ -n "$DOCKER_ID" ] && [ -n "$DOCKER_PAT" ] || { echo "ERROR: DOCKER_ID and DOCKER_PAT must not be empty" >&2; exit 2; }
  [[ "$DOCKER_ID" =~ ^[a-z0-9][a-z0-9_.-]*$ ]] || { echo "ERROR: DOCKER_ID must be lowercase letters/digits (Docker Hub username)" >&2; exit 2; }
fi

docker info >/dev/null 2>&1 || { echo "ERROR: no Docker daemon available" >&2; exit 1; }
if [ -z "$DRY_RUN" ]; then
  printf '%s' "$DOCKER_PAT" | docker login -u "$DOCKER_ID" --password-stdin >/dev/null
  echo ">> logged in to Docker Hub as $DOCKER_ID"
fi

export DOCKER_ID TAG
PLATFORM="$PLATFORMS" scripts/build-local-images.sh

if [ -n "$DRY_RUN" ]; then
  echo
  echo "DRY_RUN finished: JVM images ($PLATFORMS) are in the local Docker as $DOCKER_ID/<svc>:$TAG"
  exit 0
fi

for s in "${SVCS[@]}"; do
  echo ">> push docker.io/$DOCKER_ID/$s:$TAG"
  docker push -q "$DOCKER_ID/$s:$TAG" >/dev/null
done

echo
echo "Pushed (JVM images, $PLATFORMS):"
for s in "${SVCS[@]}"; do echo "  docker.io/$DOCKER_ID/$s:$TAG"; done
echo "Deploy: OVERLAY=aks-jvm make k8s-deploy TAG=$TAG   (or: make aks-deploy TAG=$TAG HELM_VALUES=charts/seatstorm/values-jvm.yaml)"
echo "Note: new repositories on Docker Hub are public by default; if private, the cluster needs the 'dockerhub' pull secret."
