#!/usr/bin/env bash
# Borra ambos namespaces (incluye PVC y secretos). Pide confirmacion; --yes la omite.
set -euo pipefail
if [ "${1:-}" != "--yes" ]; then
  read -r -p "Se borraran los namespaces seatstorm y seatstorm-platform (datos incluidos). Escribe 'yes' para continuar: " ans
  [ "$ans" = "yes" ] || { echo "cancelado"; exit 1; }
fi
kubectl delete namespace seatstorm seatstorm-platform --ignore-not-found --wait=true
