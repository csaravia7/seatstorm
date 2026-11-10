#!/usr/bin/env bash
# Despliega SeatStorm con Kustomize (sin Helm).
#   uso: k8s/scripts/deploy.sh [tag]        (por defecto "latest"; o una version concreta: v1.0.0, git sha...)
#   OVERLAY=aks|aks-jvm|local (por defecto aks)
#     aks     -> imagenes NATIVAS (publicadas por CI/release.yaml); limites de memoria de 256Mi
#     aks-jvm -> imagenes JVM (publicadas con `make push-images`); 512Mi/384Mi, si no OOMKilled
#   Requiere DOCKER_ID (usuario de Docker Hub): las imagenes son docker.io/$DOCKER_ID/<servicio>:<tag>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAYS="$ROOT/k8s/overlays"
OVERLAY="${OVERLAY:-aks}"
[ -d "$OVERLAYS/$OVERLAY" ] || { echo "ERROR: overlay '$OVERLAY' no existe" >&2; exit 2; }

# 0) Requisitos: kubectl y un clúster accesible
command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl no esta instalado." >&2; exit 2; }
kubectl cluster-info >/dev/null 2>&1 || { echo "ERROR: kubectl no alcanza ningun clúster. Configura el acceso (AKS: az aks get-credentials -g <rg> -n <cluster> --admin)." >&2; exit 2; }
echo "Clúster destino: $(kubectl config current-context)"

# 1) Tag de imagen: argumento, variable TAG o pregunta (por defecto "latest"; opcional una version concreta).
TAG="${1:-${TAG:-}}"
if [ -z "$TAG" ] && [ -t 0 ]; then
  read -rp "Tag de las imagenes en Docker Hub [latest]: " TAG
fi
TAG="${TAG:-latest}"

# 2) Variables / tokens previos al despliegue (pide lo que falte y lo exporta; no escribe a disco)
# shellcheck disable=SC1091
source "$ROOT/k8s/scripts/setup-env.sh"
# local: las imagenes ya estan cargadas en el nodo; sin DOCKER_ID se usa el nombre por defecto de compose.
[ -n "${DOCKER_ID:-}" ] || [ "$OVERLAY" != local ] || DOCKER_ID=seatstorm

if [ -t 0 ] && [ -z "${ASSUME_YES:-}" ]; then
  read -rp "Desplegar tag '$TAG' en '$(kubectl config current-context)'? [s/N] " ok
  case "$ok" in s|S|y|Y) ;; *) echo "Cancelado."; exit 1;; esac
fi

echo "==> namespaces"
kubectl apply -k "$ROOT/k8s/base/namespaces"

echo "==> secretos"
"$ROOT/k8s/scripts/create-secrets.sh"

# Overlay temporal hermano con el tag fijado (mantiene validas las rutas relativas ../../base).
TMP="$(mktemp -d "$OVERLAYS/.deploy-XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
cp -R "$OVERLAYS/$OVERLAY/." "$TMP/"
[[ "${DOCKER_ID:-}" =~ ^[a-z0-9][a-z0-9_.-]*$ ]] || { echo "ERROR: DOCKER_ID invalido o vacio (usuario de Docker Hub, en minusculas)" >&2; exit 2; }
sed -i.bak -E -e "s/^([[:space:]]*newTag:).*/\1 \"$TAG\"/" \
  -e "s#^([[:space:]]*newName:[[:space:]]*docker\.io/)[^/]+/#\1$DOCKER_ID/#" "$TMP/kustomization.yaml" && rm -f "$TMP/kustomization.yaml.bak"

echo "==> render ($OVERLAY, docker.io/$DOCKER_ID, tag $TAG)"
if kubectl kustomize "$TMP" | grep -E 'image:.*DOCKER_ID' ; then
  echo "ERROR: el render conserva el marcador DOCKER_ID" >&2; exit 1
fi

# Los Jobs son inmutables: se recrea kafka-init en cada despliegue.
kubectl delete job kafka-init -n seatstorm-platform --ignore-not-found

echo "==> apply"
if [ "$TAG" = "latest" ]; then
  # Con :latest los nodos podrian reutilizar una imagen vieja: forzar pullPolicy Always y reiniciar tras aplicar.
  kubectl kustomize "$TMP" | sed 's/imagePullPolicy: IfNotPresent/imagePullPolicy: Always/' | kubectl apply -f -
  kubectl rollout restart deployment -n seatstorm >/dev/null
else
  kubectl apply -k "$TMP"
fi

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
if [ "$OVERLAY" != "local" ]; then
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
