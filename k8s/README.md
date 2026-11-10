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
│   ├── aks/               # imágenes NATIVAS (CI): newName/newTag, imagePullSecret, Service web LoadBalancer
│   ├── aks-jvm/           # igual que aks, con memoria 512Mi/384Mi para imágenes JVM (make push-images)
│   └── local/             # minikube/kind (opcional)
└── scripts/               # deploy.sh, destroy.sh, create-secrets.sh, setup-env.sh, build-push-dockerhub.sh
```

Uso previsto: `make k8s-deploy TAG=<git-sha>` → `k8s/scripts/deploy.sh`.

## Uso rápido

```bash
make k8s-deploy        # 1) comprueba kubectl/clúster  2) pide el tag  3) pide tokens/variables que falten
                       # 4) confirma el clúster  5) crea secretos  6) aplica y espera  7) imprime la URL
source k8s/scripts/setup-env.sh   # opcional: exporta las variables en tu shell antes de desplegar
```

`setup-env.sh` pide (entrada oculta, sin escribir a disco): `DOCKER_ID` y `DOCKER_PAT` (**obligatorias**; usuario y personal access token de tu cuenta de Docker Hub; el PAT crea el secreto `dockerhub`), `ANTHROPIC_API_KEY`/`NVIDIA_API_KEY` (opcionales) y `CONSOLE_PIN`; genera `DB_PASSWORD`, `ADMIN_TOKEN`, `SESSION_SECRET` y `TICKET_SECRET` (reutilizando los del clúster si ya existen). Lo ya exportado o en `.env` no se vuelve a preguntar. Sin interacción: `ASSUME_YES=1 k8s/scripts/deploy.sh <tag>`.

## Uso

```bash
# AKS (kubectl apuntando al clúster; .env con los secretos; DOCKER_ID/DOCKER_PAT obligatorias)
make k8s-deploy TAG=<tag>            # imágenes NATIVAS de CI -> overlay aks; imprime la IP de web
OVERLAY=aks-jvm make k8s-deploy TAG=<tag>   # imágenes JVM de `make push-images` -> overlay aks-jvm (512Mi/384Mi)
make k8s-destroy                     # = k8s/scripts/destroy.sh (pide confirmación)

# Manual
k8s/scripts/create-secrets.sh        # crea seatstorm-secrets (+ dockerhub) en ambos namespaces
# kubectl apply -k k8s/overlays/aks  # NO directo: los overlays traen el marcador docker.io/DOCKER_ID/<svc>; deploy.sh reescribe newName (docker.io/$DOCKER_ID/<svc>) y newTag en una copia temporal

# minikube / kind (las imágenes deben estar cargadas en el nodo)
OVERLAY=local k8s/scripts/deploy.sh dev   # web por NodePort 30080
```

Notas:

- Los secretos reales nunca se versionan; `base/secrets/` solo tiene la plantilla y no forma parte de ninguna kustomization.
- El Job `kafka-init` es inmutable: `deploy.sh` lo borra antes de aplicar. Con `kubectl apply -k` manual en un redespliegue, ejecuta antes `kubectl delete job kafka-init -n seatstorm-platform --ignore-not-found`.
- `app.kubernetes.io/version` y `DT_RELEASE_VERSION` se derivan del tag mediante `replacements` en los overlays.
- Imágenes: `docker.io/$DOCKER_ID/<svc>:<tag>` (cuenta personal de Docker Hub). Kustomize no lee variables de entorno, por eso `deploy.sh` exige `DOCKER_ID` y reescribe `newName`+`newTag`; falla si el render conserva el marcador `DOCKER_ID` o contiene `:latest`.
- Cuál overlay: nativas de CI (`release.yaml`) -> `aks` (Quarkus 256Mi); JVM de `make push-images` -> `aks-jvm` (512Mi, `notification` 384Mi; con 256Mi se matan por OOM).
- Validación (`ci.yaml`): los tres overlays renderizados con un id de prueba (`myuser`), `kubeconform -strict` y sin el marcador `DOCKER_ID`.
