#!/usr/bin/env bash
# Builds all 11 seatstorm images locally as JVM images (no native build, no Docker Hub needed).
# Idempotent: re-running rebuilds from the current sources (Docker layer cache makes it fast).
#
# Usage:  scripts/build-local-images.sh            # tag :dev
#         TAG=abc123 scripts/build-local-images.sh
#         SKIP_MAVEN=1 scripts/build-local-images.sh   # reuse existing target/quarkus-app
# Honours a caller-set DOCKER_HOST. Then run:
#   docker compose -f compose.yaml -f compose.local.yaml --profile apps up -d    (or docker-compose ...)
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="${TAG:-dev}"
QUARKUS_SVCS=(event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification)

if [ "${SKIP_MAVEN:-0}" != 1 ]; then
  source scripts/ensure-java.sh
  echo ">> mvn package (all Quarkus services, tests skipped)"
  ./mvnw -B -q -DskipTests clean package
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
  echo ">> seatstorm/$s:$TAG"
  [ -f "services/$s/target/quarkus-app/quarkus-run.jar" ] || { echo "missing services/$s/target/quarkus-app (run without SKIP_MAVEN)"; exit 1; }
  docker build -q -f "$DF" -t "seatstorm/$s:$TAG" "services/$s/target/quarkus-app" >/dev/null
done

echo ">> seatstorm/bff:$TAG";            docker build -q -t "seatstorm/bff:$TAG" web/bff >/dev/null
echo ">> seatstorm/bot-swarm:$TAG";      docker build -q -t "seatstorm/bot-swarm:$TAG" web/bots >/dev/null
echo ">> seatstorm/web:$TAG";            docker build -q -t "seatstorm/web:$TAG" -f web/Dockerfile web >/dev/null
echo ">> seatstorm/assistant-agent:$TAG"; docker build -q -t "seatstorm/assistant-agent:$TAG" -f services/assistant-agent/Dockerfile . >/dev/null
echo "done: 11 images tagged :$TAG"
