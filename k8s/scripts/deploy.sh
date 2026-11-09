#!/usr/bin/env bash
# Despliega SeatStorm con Kustomize (sin Helm).
#   uso: k8s/scripts/deploy.sh <tag>        (tag = git sha; nunca "latest")
#   OVERLAY=aks|local (por defecto aks)
set -euo pipefail

TAG="${1:-}"
OVERLAY="${OVERLAY:-aks}"
[ -n "$TAG" ] || { echo "uso: $0 <tag>" >&2; exit 2; }
[ "$TAG" != "latest" ] || { echo "ERROR: ':latest' no esta permitido" >&2; exit 2; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAYS="$ROOT/k8s/overlays"
[ -d "$OVERLAYS/$OVERLAY" ] || { echo "ERROR: overlay '$OVERLAY' no existe" >&2; exit 2; }

echo "==> namespaces"
kubectl apply -k "$ROOT/k8s/base/namespaces"

echo "==> secretos"
"$ROOT/k8s/scripts/create-secrets.sh"

# Overlay temporal hermano con el tag fijado (mantiene validas las rutas relativas ../../base).
TMP="$(mktemp -d "$OVERLAYS/.deploy-XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
cp -R "$OVERLAYS/$OVERLAY/." "$TMP/"
sed -i.bak -E "s/^([[:space:]]*newTag:).*/\1 \"$TAG\"/" "$TMP/kustomization.yaml" && rm -f "$TMP/kustomization.yaml.bak"

echo "==> render ($OVERLAY, tag $TAG)"
if kubectl kustomize "$TMP" | grep -E 'image:.*:latest' ; then
  echo "ERROR: el render contiene :latest" >&2; exit 1
fi

# Los Jobs son inmutables: se recrea kafka-init en cada despliegue.
kubectl delete job kafka-init -n seatstorm-platform --ignore-not-found

echo "==> apply"
kubectl apply -k "$TMP"

echo "==> plataforma"
kubectl rollout status statefulset/postgres -n seatstorm-platform --timeout=300s
kubectl rollout status statefulset/kafka -n seatstorm-platform --timeout=300s
kubectl rollout status deployment/apicurio -n seatstorm-platform --timeout=300s
kubectl rollout status deployment/kafka-ui -n seatstorm-platform --timeout=300s
kubectl wait --for=condition=complete job/kafka-init -n seatstorm-platform --timeout=600s

echo "==> aplicacion"
for d in event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification \
         assistant-agent bff bot-swarm web; do
  kubectl rollout status "deployment/$d" -n seatstorm --timeout=300s
done

echo "==> Service web"
if [ "$OVERLAY" = "aks" ]; then
  for _ in $(seq 1 60); do
    IP="$(kubectl get svc web -n seatstorm -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
    [ -n "$IP" ] && break
    sleep 5
  done
  kubectl get svc web -n seatstorm
  echo "URL: http://${IP:-<pendiente>}"
else
  kubectl get svc web -n seatstorm
  echo "Local: minikube service web -n seatstorm   |   kind: http://localhost:30080 (con extraPortMappings)"
fi
