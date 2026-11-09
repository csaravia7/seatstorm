# k8s/ — Manifiestos Kubernetes (YAML plano + Kustomize)

Despliegue sin Helm: `kubectl apply -k`. Alternativa equivalente a `charts/` (ver sección 11.3.1 de SEATSTORM-SPEC.md).

```text
k8s/
├── base/
│   ├── namespaces/        # seatstorm-platform, seatstorm
│   ├── secrets/           # plantilla seatstorm-secrets.example.yaml (NUNCA valores reales)
│   ├── platform/          # kafka, postgres, apicurio, kafka-ui, kafka-init (ns seatstorm-platform)
│   └── apps/              # un directorio por servicio: deployment.yaml + service.yaml (ns seatstorm)
├── overlays/
│   ├── aks/               # tag de imagen, imagePullSecret, réplicas, Service web LoadBalancer
│   └── local/             # minikube/kind (opcional)
└── scripts/               # deploy.sh, destroy.sh, create-secrets.sh
```

Uso previsto: `make k8s-deploy TAG=<git-sha>` → `k8s/scripts/deploy.sh`.

## Uso

```bash
# AKS (kubectl apuntando al clúster; .env con los secretos; opcional DOCKERHUB_USER/DOCKERHUB_TOKEN)
make k8s-deploy TAG=<git-sha>        # = k8s/scripts/deploy.sh <git-sha>; imprime la IP de web
make k8s-destroy                     # = k8s/scripts/destroy.sh (pide confirmación)

# Manual
k8s/scripts/create-secrets.sh        # crea seatstorm-secrets (+ dockerhub) en ambos namespaces
kubectl apply -k k8s/overlays/aks    # tag en images[].newTag (por defecto "dev")

# minikube / kind (las imágenes deben estar cargadas en el nodo)
OVERLAY=local k8s/scripts/deploy.sh dev   # web por NodePort 30080
```

Notas:

- Los secretos reales nunca se versionan; `base/secrets/` solo tiene la plantilla y no forma parte de ninguna kustomization.
- El Job `kafka-init` es inmutable: `deploy.sh` lo borra antes de aplicar. Con `kubectl apply -k` manual en un redespliegue, ejecuta antes `kubectl delete job kafka-init -n seatstorm-platform --ignore-not-found`.
- `app.kubernetes.io/version` y `DT_RELEASE_VERSION` se derivan del tag mediante `replacements` en los overlays.
- Validación: `kubectl kustomize k8s/overlays/aks | kubeconform -strict` y sin `:latest` (corre en `ci.yaml`).
