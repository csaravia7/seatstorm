# SeatStorm — Runbook de la demo

Fuente: `SEATSTORM-SPEC.md` secciones 11.2, 11.3, 11.3.1, 11.5, 14.2 y 14.3.

## 1. Crear el clúster AKS (una vez)

```bash
az group create -n rg-seatstorm -l <region>
az aks create -g rg-seatstorm -n aks-seatstorm --node-count 3 --node-vm-size Standard_D4s_v5 --generate-ssh-keys
az aks get-credentials -g rg-seatstorm -n aks-seatstorm --admin      # --admin evita depender de kubelogin
```

Tres nodos de 4 vCPU / 16 GB dejan margen para el OneAgent. Para la escena de 1000 bots, usar 4 nodos (`az aks scale`).

## 2. Namespaces, `imagePullSecret` y secretos

Las descargas anónimas de Docker Hub están muy limitadas y los nodos salen por la misma IP, por eso se usa una cuenta autenticada (token de **solo lectura** para el clúster; el de escritura es solo para CI). Aplica también a las imágenes de terceros.

Vía rápida (lee `.env`, crea ambos namespaces, `seatstorm-secrets`, `dockerhub` y enlaza la ServiceAccount `default`):

```bash
export DOCKERHUB_USER=... DOCKERHUB_TOKEN=...
k8s/scripts/create-secrets.sh
```

Vía manual equivalente:

```bash
kubectl create ns seatstorm-platform && kubectl create ns seatstorm
for ns in seatstorm-platform seatstorm; do
  kubectl create secret docker-registry dockerhub -n $ns --docker-server=https://index.docker.io/v1/ \
    --docker-username="$DH_USER" --docker-password="$DH_TOKEN"
  kubectl patch serviceaccount default -n $ns -p '{"imagePullSecrets":[{"name":"dockerhub"}]}'
  kubectl create secret generic seatstorm-secrets -n $ns \
    --from-literal=DB_PASSWORD=... --from-literal=ADMIN_TOKEN=... --from-literal=SESSION_SECRET=... \
    --from-literal=CONSOLE_PIN=... --from-literal=TICKET_SECRET=... \
    --from-literal=ANTHROPIC_API_KEY=... --from-literal=NVIDIA_API_KEY=...
done
```

Nunca versionar valores reales (`.env` y `k8s/**/secrets.yaml` están en `.gitignore`).

## 3. Desplegar (la víspera, para que las imágenes queden en caché de los nodos)

Dos vías equivalentes (mismos recursos, etiquetas, sondas y variables).

**Helm** (principal en CI; GitHub Actions `deploy-aks` con la entrada `tag`):

```bash
make aks-deploy TAG=<git-sha>
# = helm upgrade --install platform charts/platform -n seatstorm-platform --create-namespace
#   helm upgrade --install seatstorm charts/seatstorm -n seatstorm --create-namespace --set image.tag=<git-sha>
kubectl get svc web -n seatstorm        # IP externa
```

**Kustomize / kubectl** (sin Helm):

```bash
make k8s-deploy TAG=<git-sha>           # = k8s/scripts/deploy.sh <git-sha>
make k8s-destroy                        # borra ambos namespaces (pide confirmación)
```

No mezclar las dos vías sobre el mismo clúster sin destruir antes (los recursos no tienen el mismo dueño para Helm).

Workflow `deploy-aks`: requiere el secreto de repositorio `KUBE_CONFIG` (kubeconfig de `az aks get-credentials --admin`, en base64: `base64 -w0 ~/.kube/config`) y que los secretos del paso 2 ya existan en el clúster. Release de imágenes: `release.yaml` (tag `v*` o manual) publica `seatstorm/<svc>:<git-sha>`; usa ese sha como `TAG`. Nunca `:latest`.

Acceso a Kafka UI: `kubectl port-forward -n seatstorm-platform svc/kafka-ui 8085:8080` (o `--set kafkaUi.expose=true` en el chart de plataforma).

Escala de `seat-inventory` (escena 5): `kubectl scale deploy/seat-inventory -n seatstorm --replicas=12`. Con Helm, un `helm upgrade` posterior la devuelve a 3.

## 4. Dynatrace (lo instala el usuario)

Orden: primero el Operator y el DynaKube, después las cargas de SeatStorm (o `kubectl rollout restart deploy -n seatstorm` y `-n seatstorm-platform` si ya estaban). Detalle en la sección 11.5 de la especificación. Los pods ya llevan `app.kubernetes.io/*` y `DT_RELEASE_VERSION` / `DT_RELEASE_PRODUCT`.

## 5. Lista de verificación

### La víspera

- [ ] `helm upgrade` de ambos charts (o `make k8s-deploy`) con el `TAG` de la demo; imágenes en caché de los nodos.
- [ ] Dynatrace Operator instalado **antes** de las cargas; `kubectl rollout restart` si ya estaban.
- [ ] `make smoke` y `tests/invariants.sh` contra AKS.
- [ ] Correr las seis escenas completas; grabar el video de respaldo.
- [ ] Verificar el proveedor de LLM elegido: primer token < 8 s y 20 usuarios chateando a la vez sin superar el límite de tasa.
- [ ] Docker Hub: `imagePullSecret` presente en ambos namespaces (`kubectl get secret dockerhub -n seatstorm` y `-n seatstorm-platform`).
- [ ] `kubectl get pods -A | grep -v Running` sin pods en error; `kubectl get job kafka-init -n seatstorm-platform` completado.

### 30 minutos antes

- [ ] `make demo-reset`; verificar 360 asientos verdes y venta `CLOSED`.
- [ ] Pestañas abiertas: `/stage`, `/console`, Kafka UI, Dynatrace.
- [ ] Probar el QR con un móvil en la red del recinto.
- [ ] Subir el timeout de inactividad del Codespace si la demo corre ahí.

## 6. Guion de la demo (resumen; se dispara desde `/console`, entre escenas **Reset**)

| # | Escena | Qué demuestra |
|---|---|---|
| 1 | Apertura (Abrir venta; Bots 200 burst) | Pico de carga, fan-out, desacoplamiento |
| 2 | Duelo (dos voluntarios, `PLATEA-A:A01`) | Particionado por sección; sin sobreventa |
| 3 | Reloj (comprar y no pagar; 60 s) | Saga, TTL y compensación |
| 4 | Pasarela caída (Pago `PAUSED`) | Resiliencia, backlog, reembolso por retención vencida (> ~2 min) |
| 5 | Escala (Bots 1000; `kubectl scale ... --replicas=12`) | Escalado por particiones |
| 6 | Agente ("¿Por qué perdí mi asiento?") | IA sobre datos de eventos |

## 7. Problemas frecuentes

| Síntoma | Causa probable | Solución |
|---|---|---|
| El mapa no se actualiza en los celulares | SSE cortado por un proxy o Wi-Fi | Recargar; verificar `proxy_buffering off` en `/live`; el cliente vuelve a pedir `/api/seatmap` |
| Pods en `ImagePullBackOff` | Límite de Docker Hub o falta el `imagePullSecret` | Crear el secreto y enlazarlo a la ServiceAccount `default` (paso 2) y borrar los pods afectados |
| El broker no arranca en AKS | Variable `KAFKA_PORT` inyectada | `enableServiceLinks: false` (comprobar: `kubectl exec -n seatstorm-platform kafka-0 -- env \| grep KAFKA_PORT` debe salir vacío) |
| Faltan tópicos / `kafka-init` falló | Broker aún no listo, o Job inmutable en redespliegue manual | `kubectl delete job kafka-init -n seatstorm-platform` y volver a aplicar; `kubectl logs job/kafka-init -n seatstorm-platform` |
| Asientos "pegados" en ámbar tras una caída | Job de expiración detenido | Comprobar réplicas de `seat-inventory`; el job se reanuda solo |
| Órdenes en `PAYING` indefinidamente | `payment-sim` en `PAUSED` o sin réplicas | Console → Pago `OK` |
| Un servicio nativo no aparece con trazas | SPK-04 | Aplicar el plan B (OTLP) en ese servicio |
| El agente no responde | Clave, cuota o límite de tasa del proveedor (NVIDIA gratis: ~40 peticiones/min, sin verificar) | Cambiar `LLM_PROVIDER` o activar el respaldo; el chat muestra el texto de respaldo; el resto de la demo no depende de él |
| Códigos 429 en la sala | Límite por IP | Verificar el límite de 300/min por IP en `POST /api/session` |
| Pods `Pending` al escalar a 12 réplicas / 1000 bots | Falta de CPU en los nodos | `az aks scale -g rg-seatstorm -n aks-seatstorm --node-count 4` |

## 8. Recuperación

- **Reset de emergencia:** `make demo-reset`. Si algún servicio no responde al reset: `kubectl rollout restart deploy -n seatstorm` y volver a ejecutarlo.
- **Rollback de una versión:** `helm rollback seatstorm -n seatstorm`, o `make k8s-deploy TAG=<sha-anterior>`.
- **Empezar de cero (borra datos):** `make k8s-destroy` (o `kubectl delete ns seatstorm seatstorm-platform`) y repetir los pasos 2 y 3.

## 9. Plan B

- Video de respaldo de las seis escenas: **PENDIENTE — enlazar aquí tras grabarlo en la víspera** (US-E12-03).
- Si AKS no está disponible: `make demo-up` levanta todo con Docker Hub en el Codespace (mismas imágenes y mismo `TAG`).
- Si el LLM falla: la demo continúa; el chat muestra el texto de respaldo.
