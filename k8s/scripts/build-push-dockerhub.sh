#!/usr/bin/env bash
# Construye y publica en Docker Hub (multi-arquitectura) las imagenes NO nativas: bff, bot-swarm, web, assistant-agent.
# Las 7 imagenes Quarkus nativas las publica CI (release.yaml). Uso manual / plan B desde un equipo con Docker.
#   uso: k8s/scripts/build-push-dockerhub.sh
# Variables: DOCKER_ID (obligatoria), DOCKER_PAT (opcional; si falta pide login), IMAGE_TAG (git sha), PLATFORMS (linux/amd64,linux/arm64)
set -euo pipefail
cd "$(dirname "$0")/../.."

DOCKER_ID="${DOCKER_ID:?DOCKER_ID es obligatoria (tu usuario de Docker Hub)}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
BUILDER=seatstorm-multiarch

docker info >/dev/null 2>&1 || { echo ">> No hay daemon de Docker disponible" >&2; exit 1; }
docker buildx version >/dev/null 2>&1 || { echo ">> Falta 'docker buildx'" >&2; exit 1; }
docker buildx inspect "$BUILDER" >/dev/null 2>&1 || docker buildx create --name "$BUILDER" --driver docker-container >/dev/null
docker buildx inspect "$BUILDER" --bootstrap >/dev/null

if [ -n "${DOCKER_PAT:-}" ]; then
  printf '%s' "$DOCKER_PAT" | docker login -u "$DOCKER_ID" --password-stdin
else
  docker login -u "$DOCKER_ID"
fi

# nombre | contexto | Dockerfile  (igual que release.yaml)
for spec in "bff|web/bff|web/bff/Dockerfile" "bot-swarm|web/bots|web/bots/Dockerfile" \
            "web|web|web/Dockerfile" "assistant-agent|.|services/assistant-agent/Dockerfile"; do
  IFS='|' read -r name ctx file <<<"$spec"
  echo ">> docker.io/${DOCKER_ID}/${name}:${IMAGE_TAG} (${PLATFORMS})"
  docker buildx build --builder "$BUILDER" --platform "$PLATFORMS" -f "$file" \
    -t "docker.io/${DOCKER_ID}/${name}:${IMAGE_TAG}" --push "$ctx"
done
echo ">> Publicadas con tag ${IMAGE_TAG}. Desplegar: make k8s-deploy TAG=${IMAGE_TAG}"
