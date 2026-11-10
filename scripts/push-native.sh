#!/usr/bin/env bash
# Builds the Quarkus services as GraalVM NATIVE images and pushes everything to your personal Docker Hub.
# Uses services/<svc>/src/main/docker/Dockerfile.native-micro (GraalVM CE builder -> ubi9 micro runtime).
#
#   scripts/push-native.sh                       # prompts for what is missing; builds + pushes the 7 native services + 4 other images
#   scripts/push-native.sh seat-inventory        # only the given Quarkus service(s)
#   make push-native TAG=<tag>
#
# Variables:
#   DOCKER_ID / DOCKER_PAT   Docker Hub username / access token with write access (prompted if missing; PAT hidden)
#   TAG                      image tag (default: latest; or a specific version, e.g. v1.0.0 / a git sha)
#   PLATFORMS                default linux/amd64 (AKS x86 nodes). Use linux/arm64 for arm nodes.
#   NATIVE_XMX               max heap for native-image (default 4g). Each native build needs ~6-8 GB of RAM in Docker.
#   SKIP_OTHERS=1            do not build/push bff, bot-swarm, web, assistant-agent
#   DRY_RUN=1                build only (loads into the local Docker, no login, no push) — use it to validate
#   DT_ENV_URL + DT_API_TOKEN  Dynatrace GraalVM Native Image module (-Pdynatrace-native). Prompted for when missing
#                            (Enter = skip); DT_SKIP=1 never asks. The token is hidden and sent as a BuildKit secret.
# Builds are sequential on purpose (RAM). Expect ~3-6 min per service. Then deploy:  make k8s-deploy TAG=<tag>
set -euo pipefail
cd "$(dirname "$0")/.."

ALL=(event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification)
SERVICES=("$@"); [ ${#SERVICES[@]} -gt 0 ] || SERVICES=("${ALL[@]}")
for s in "${SERVICES[@]}"; do
  [ -f "services/$s/src/main/docker/Dockerfile.native-micro" ] || { echo "ERROR: unknown Quarkus service '$s' (valid: ${ALL[*]})" >&2; exit 2; }
done

TAG="${TAG:-latest}"
PLATFORMS="${PLATFORMS:-linux/amd64}"
NATIVE_XMX="${NATIVE_XMX:-4g}"
DRY_RUN="${DRY_RUN:-}"

docker info >/dev/null 2>&1 || { echo "ERROR: no Docker daemon available" >&2; exit 1; }
docker buildx version >/dev/null 2>&1 || { echo "ERROR: 'docker buildx' is required" >&2; exit 1; }

mem_kb=$(docker info --format '{{.MemTotal}}' 2>/dev/null | awk '{printf "%d", $1/1024}')
if [ "${mem_kb:-0}" -gt 0 ] && [ "$mem_kb" -lt 7000000 ]; then
  echo "WARN: Docker has only $((mem_kb/1024)) MB of RAM; native builds need ~6-8 GB. They may be OOM-killed (try NATIVE_XMX=3g, or build in the Codespace/CI)." >&2
fi
host_arch="$(uname -m)"; host_arch="${host_arch/x86_64/amd64}"; host_arch="${host_arch/aarch64/arm64}"
case "$PLATFORMS" in *"$host_arch"*) ;; *) echo "WARN: PLATFORMS=$PLATFORMS differs from this host ($host_arch): native-image under emulation is extremely slow and often fails. Build on a matching machine (Codespaces is amd64) or via CI release.yaml." >&2;; esac

if [ -z "$DRY_RUN" ]; then
  if [ -z "${DOCKER_ID:-}" ]; then
    [ -t 0 ] || { echo "ERROR: DOCKER_ID is required (your Docker Hub username)" >&2; exit 2; }
    read -rp "DOCKER_ID (Docker Hub username): " DOCKER_ID
  fi
  if [ -z "${DOCKER_PAT:-}" ]; then
    [ -t 0 ] || { echo "ERROR: DOCKER_PAT is required (Docker Hub access token)" >&2; exit 2; }
    read -rsp "DOCKER_PAT (hidden): " DOCKER_PAT; echo
  fi
  [[ "${DOCKER_ID:-}" =~ ^[a-z0-9][a-z0-9_.-]*$ ]] || { echo "ERROR: invalid DOCKER_ID (lowercase Docker Hub username)" >&2; exit 2; }
  [ -n "${DOCKER_PAT:-}" ] || { echo "ERROR: DOCKER_PAT must not be empty" >&2; exit 2; }
  printf '%s' "$DOCKER_PAT" | docker login -u "$DOCKER_ID" --password-stdin >/dev/null
  echo ">> logged in to Docker Hub as $DOCKER_ID"
else
  DOCKER_ID="${DOCKER_ID:-seatstorm}"
  echo ">> DRY_RUN: building only, nothing is pushed"
fi

BUILDER=seatstorm-native
if [ -z "$DRY_RUN" ]; then
  docker buildx inspect "$BUILDER" >/dev/null 2>&1 || docker buildx create --name "$BUILDER" --driver docker-container >/dev/null
  docker buildx inspect "$BUILDER" --bootstrap >/dev/null
  BUILD_OUT=(--builder "$BUILDER" --push)
else
  BUILD_OUT=(--load)   # --load supports a single platform only
fi

# Dynatrace GraalVM Native Image module (instruments the native binary at build time).
# Asked for when missing and stdin is a terminal; press Enter to skip (the image is then built WITHOUT Dynatrace).
#   DT_ENV_URL    environment URL, e.g. https://abc12345.live.dynatrace.com   (no trailing slash)
#   DT_API_TOKEN  API token with the InstallerDownload scope (hidden input; passed to the build as a BuildKit secret)
if [ -z "${DT_ENV_URL:-}${DT_API_TOKEN:-}" ] && [ -t 0 ] && [ -z "${DT_SKIP:-}" ]; then
  echo ">> Dynatrace native module (optional): press Enter to build without it."
  read -rp "   DT_ENV_URL (e.g. https://abc12345.live.dynatrace.com): " DT_ENV_URL
  if [ -n "$DT_ENV_URL" ]; then read -rsp "   DT_API_TOKEN (InstallerDownload scope, hidden): " DT_API_TOKEN; echo; fi
fi
dt_args=()
if [ -n "${DT_ENV_URL:-}" ] || [ -n "${DT_API_TOKEN:-}" ]; then
  [ -n "${DT_ENV_URL:-}" ] && [ -n "${DT_API_TOKEN:-}" ] || { echo "ERROR: DT_ENV_URL and DT_API_TOKEN must be provided together" >&2; exit 2; }
  DT_ENV_URL="${DT_ENV_URL%/}"
  [[ "$DT_ENV_URL" =~ ^https://[A-Za-z0-9.-]+(/e/[A-Za-z0-9-]+)?$ ]] || { echo "ERROR: DT_ENV_URL must look like https://<env-id>.live.dynatrace.com (https, no trailing path)" >&2; exit 2; }
  export DT_API_TOKEN
  echo ">> Dynatrace GraalVM Native Image module ENABLED ($DT_ENV_URL)"
  dt_args=(--build-arg "DT_ENV_URL=$DT_ENV_URL" --secret id=dt_api_token,env=DT_API_TOKEN)
else
  echo ">> Building WITHOUT the Dynatrace native module (set DT_ENV_URL + DT_API_TOKEN to enable it)"
fi

for s in "${SERVICES[@]}"; do
  image="docker.io/${DOCKER_ID}/${s}:${TAG}"
  echo ">> [native] $image ($PLATFORMS, native-image xmx=$NATIVE_XMX) — takes several minutes"
  start=$SECONDS
  DOCKER_BUILDKIT=1 docker buildx build "${BUILD_OUT[@]}" --platform "$PLATFORMS" \
    -f "services/$s/src/main/docker/Dockerfile.native-micro" \
    --build-arg "MVN_ARGS=-Dquarkus.native.native-image-xmx=${NATIVE_XMX}" ${dt_args[@]+"${dt_args[@]}"} \
    -t "$image" .
  echo ">> done $s in $((SECONDS-start))s"
done

if [ -z "${SKIP_OTHERS:-}" ] && [ -z "$DRY_RUN" ]; then
  echo ">> building/pushing the non-Quarkus images (bff, bot-swarm, web, assistant-agent)"
  DOCKER_ID="$DOCKER_ID" DOCKER_PAT="$DOCKER_PAT" IMAGE_TAG="$TAG" PLATFORMS="$PLATFORMS" k8s/scripts/build-push-dockerhub.sh
fi

echo
if [ -n "$DRY_RUN" ]; then echo "DRY_RUN finished: images are in the local Docker as docker.io/$DOCKER_ID/<svc>:$TAG"; else
  echo "Published (native) with tag $TAG:"; for s in "${SERVICES[@]}"; do echo "  docker.io/$DOCKER_ID/$s:$TAG"; done
  echo "Deploy: make k8s-deploy TAG=$TAG   (overlay aks = native memory limits)"
fi
