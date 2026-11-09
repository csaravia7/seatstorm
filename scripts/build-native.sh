#!/usr/bin/env bash
# Native Linux executables via the GraalVM CE builder in a container (no local GraalVM needed).
# Usage: scripts/build-native.sh [service ...]   (default: all 7 Quarkus services)
# Extra Maven args: MVN_ARGS="-Pdynatrace-native" scripts/build-native.sh seat-inventory
set -euo pipefail
cd "$(dirname "$0")/.."
SERVICES=("$@")
[ ${#SERVICES[@]} -gt 0 ] || SERVICES=(event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification)
for s in "${SERVICES[@]}"; do
  ./mvnw -B package -pl "services/$s" -am -Dnative -DskipTests -Dquarkus.native.container-build=true ${MVN_ARGS:-}
  ls -lh "services/$s"/target/*-runner
done
