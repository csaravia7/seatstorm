#!/usr/bin/env bash
# Builds all 11 seatstorm images locally as JVM images (no native build, no Docker Hub login needed; set DOCKER_ID to tag them for your Docker Hub account).
# Idempotent: re-running rebuilds from the current sources (Docker layer cache makes it fast).
#
# Usage:  scripts/build-local-images.sh            # tag :dev
#         TAG=abc123 DOCKER_ID=myuser scripts/build-local-images.sh   # -> myuser/<svc>:abc123
#         SKIP_MAVEN=1 scripts/build-local-images.sh   # reuse existing target/quarkus-app
#         PLATFORM=linux/amd64 scripts/build-local-images.sh   # target another architecture (e.g. amd64 AKS nodes from an Apple Silicon Mac)
# Honours a caller-set DOCKER_HOST. Then run:
#   docker compose -f compose.yaml -f compose.local.yaml --profile apps up -d    (or docker-compose ...)
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="${TAG:-dev}"
[ -n "${DOCKER_ID:-}" ] || DOCKER_ID="$(sed -n 's/^DOCKER_ID=//p' .env 2>/dev/null | head -1 || true)"   # same value compose reads from .env
REPO="${DOCKER_ID:-seatstorm}"   # images: $REPO/<svc>:$TAG (default `seatstorm` only so local builds work without a Docker Hub account)
QUARKUS_SVCS=(event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification)
PLAT=(); [ -z "${PLATFORM:-}" ] || PLAT=(--platform "$PLATFORM")

if [ "${SKIP_MAVEN:-0}" != 1 ]; then
  source scripts/ensure-java.sh
  ROOT="$(pwd)"
  # In a Codespace the IDE (Java language server / m2e) rewrites target/ folders while Maven runs, which breaks the
  # Quarkus build with random errors (missing classes/beans). Build in an isolated copy outside the workspace.
  ISOLATED_BUILD="${ISOLATED_BUILD:-$([ -n "${CODESPACE_NAME:-}" ] && echo 1 || echo 0)}"
  if [ "$ISOLATED_BUILD" = 1 ]; then
    WORK="${BUILD_DIR:-$HOME/.cache/seatstorm-build}"
    echo ">> isolated build in $WORK"
    rm -rf "$WORK"; mkdir -p "$WORK"
    tar -C "$ROOT" --exclude=.git --exclude=node_modules --exclude=target --exclude=.venv --exclude=dist -cf - . | tar -C "$WORK" -xf -
    echo ">> mvn package (all Quarkus services, tests skipped)"
    (cd "$WORK" && ./mvnw -B -q -DskipTests clean package)
    for s in "${QUARKUS_SVCS[@]}"; do
      rm -rf "$ROOT/services/$s/target/quarkus-app"; mkdir -p "$ROOT/services/$s/target"
      cp -R "$WORK/services/$s/target/quarkus-app" "$ROOT/services/$s/target/quarkus-app"
    done
  else
    echo ">> mvn package (all Quarkus services, tests skipped)"
    ./mvnw -B -q -DskipTests clean package
  fi
fi

# Thin runtime image around the already-built fast-jar (avoids a Maven build inside Docker).
DF="$(mktemp)"; trap 'rm -f "$DF"' EXIT
cat > "$DF" <<'DOCKERFILE'
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY lib/ ./lib/
COPY *.jar ./
COPY app/ ./app/
COPY quarkus/ ./quarkus/
EXPOSE 8080
USER 1001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "quarkus-run.jar"]
DOCKERFILE

for s in "${QUARKUS_SVCS[@]}"; do
  echo ">> ${REPO}/$s:$TAG"
  [ -f "services/$s/target/quarkus-app/quarkus-run.jar" ] || { echo "missing services/$s/target/quarkus-app (run without SKIP_MAVEN)"; exit 1; }
  docker build -q ${PLAT[@]+"${PLAT[@]}"} -f "$DF" -t "${REPO}/$s:$TAG" "services/$s/target/quarkus-app" >/dev/null
done

echo ">> ${REPO}/bff:$TAG";            docker build -q ${PLAT[@]+"${PLAT[@]}"} -t "${REPO}/bff:$TAG" web/bff >/dev/null
echo ">> ${REPO}/bot-swarm:$TAG";      docker build -q ${PLAT[@]+"${PLAT[@]}"} -t "${REPO}/bot-swarm:$TAG" web/bots >/dev/null
echo ">> ${REPO}/web:$TAG";            docker build -q ${PLAT[@]+"${PLAT[@]}"} -t "${REPO}/web:$TAG" -f web/Dockerfile web >/dev/null
echo ">> ${REPO}/assistant-agent:$TAG"; docker build -q ${PLAT[@]+"${PLAT[@]}"} -t "${REPO}/assistant-agent:$TAG" -f services/assistant-agent/Dockerfile . >/dev/null
echo "done: 11 images tagged :$TAG"
