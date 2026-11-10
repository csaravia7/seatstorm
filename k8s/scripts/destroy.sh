#!/usr/bin/env bash
# Borra todo lo que crea el despliegue de SeatStorm en el clúster ACTUAL de kubectl:
#   - los namespaces seatstorm y seatstorm-platform, y con ellos Deployments, StatefulSets, Services (el LoadBalancer
#     de Azure se libera con el Service), Jobs, ConfigMaps, secretos (seatstorm-secrets, dockerhub), releases de Helm
#     y los PVC de Postgres y Kafka;
#   - avisa si quedan PersistentVolumes huerfanos de esos namespaces (p. ej. una StorageClass con reclaimPolicy Retain).
# NO borra: el clúster AKS / su grupo de recursos (sigue facturando), el Dynatrace Operator ni las imagenes de Docker Hub.
#   uso: k8s/scripts/destroy.sh [--yes]      (--yes omite la confirmacion)
set -euo pipefail

NAMESPACES=(seatstorm seatstorm-platform)

command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl no esta instalado." >&2; exit 2; }
kubectl cluster-info >/dev/null 2>&1 || { echo "ERROR: kubectl no alcanza ningun clúster." >&2; exit 2; }
ctx="$(kubectl config current-context)"
echo "Clúster destino: $ctx"

present=()
for ns in "${NAMESPACES[@]}"; do
  if kubectl get namespace "$ns" >/dev/null 2>&1; then present+=("$ns"); fi
done

if [ ${#present[@]} -eq 0 ]; then
  echo "No existen los namespaces ${NAMESPACES[*]} en '$ctx': nada que borrar."
else
  if [ "${1:-}" != "--yes" ]; then
    read -r -p "Se borraran los namespaces ${present[*]} en '$ctx' (datos incluidos). Escribe 'yes' para continuar: " ans
    [ "$ans" = "yes" ] || { echo "cancelado"; exit 1; }
  fi
  kubectl delete namespace "${present[@]}" --ignore-not-found --wait=true --timeout=10m
fi

# Los PV con reclaimPolicy Delete desaparecen solos tras borrar los PVC (puede tardar); se espera un poco.
orphans() {
  kubectl get pv -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.claimRef.namespace}{"\n"}{end}' 2>/dev/null |
    awk '$2=="seatstorm" || $2=="seatstorm-platform" {print $1}'
}
left="$(orphans || true)"
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  [ -n "$left" ] || break
  sleep 5; left="$(orphans || true)"
done
if [ -n "$left" ]; then
  echo "AVISO: quedan PersistentVolumes de SeatStorm (discos que siguen facturando):" >&2
  echo "$left" | sed 's/^/  /' >&2
  echo "       Borralos con: kubectl delete pv <nombre>   (y revisa el disco en el grupo de recursos MC_* de Azure)" >&2
fi

echo "Listo. SeatStorm ya no esta en '$ctx'."
echo "El clúster AKS sigue activo y facturando. Para borrarlo tambien:  az group delete -n <grupo-de-recursos> --yes --no-wait"
echo "(p. ej. rg-seatstorm, el de la guia del README). El Dynatrace Operator, si lo instalaste, tampoco se toca."
