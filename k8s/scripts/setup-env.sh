#!/usr/bin/env bash
# Prepara las variables que SeatStorm necesita ANTES de cualquier despliegue en Kubernetes.
# Pide (con entrada oculta para tokens) lo que falte y lo deja EXPORTADO en tu shell. No escribe nada en disco.
#
#   source k8s/scripts/setup-env.sh      # exporta en tu terminal (recomendado)
#   k8s/scripts/setup-env.sh --check     # solo muestra que falta, sin preguntar
#
# deploy.sh lo ejecuta automaticamente, no hace falta llamarlo antes.
# Variables ya exportadas (o presentes en .env) no se vuelven a preguntar.

_ss_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
_ss_check=0; [ "${1:-}" = "--check" ] && _ss_check=1
_ss_tty=0; [ -t 0 ] && _ss_tty=1

# Carga .env solo para variables que NO estan ya exportadas (lo exportado manda).
if [ -f "$_ss_root/.env" ]; then
  while IFS='=' read -r _k _v; do
    case "$_k" in ''|\#*) continue;; esac
    [[ "$_k" =~ ^[A-Z0-9_]+$ ]] || continue
    [ -z "${!_k:-}" ] && export "$_k=$_v"
  done < "$_ss_root/.env"
fi

_ss_rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}" || true; }
_ss_placeholder() { case "${1:-}" in ''|change-me*|seatstorm) return 0;; *) return 1;; esac; }

# Si el clúster ya tiene el secreto, se reutiliza (cambiar DB_PASSWORD rompería el PVC de Postgres existente).
_ss_cluster() {
  command -v kubectl >/dev/null 2>&1 || return 1
  kubectl get secret seatstorm-secrets -n seatstorm -o "jsonpath={.data.$1}" 2>/dev/null | base64 -d 2>/dev/null
}

# _ss_ask VAR "descripcion" [secret|plain] [requerido|opcional] [default]
_ss_ask() {
  local var="$1" desc="$2" mode="${3:-plain}" req="${4:-opcional}" def="${5:-}" val=""
  [ -n "${!var:-}" ] && ! _ss_placeholder "${!var}" && return 0
  if [ "$_ss_check" = 1 ] || [ "$_ss_tty" = 0 ]; then
    [ "$req" = requerido ] && echo "FALTA  $var  — $desc"
    return 0
  fi
  local prompt="$desc"; [ -n "$def" ] && prompt="$prompt [$def]"
  [ "$req" = opcional ] && prompt="$prompt (Enter para omitir)"
  if [ "$mode" = secret ]; then read -rsp "  $prompt: " val; echo; else read -rp "  $prompt: " val; fi
  val="${val:-$def}"
  if [ -n "$val" ]; then export "$var=$val"; elif [ "$req" = requerido ]; then
    echo "  $var es obligatorio." >&2; _ss_ask "$var" "$desc" "$mode" "$req" "$def"
  fi
}

# Secretos generados: reutiliza los del clúster, si no, crea valores aleatorios.
# DB_PASSWORD se reutiliza SIEMPRE que el clúster ya lo tenga (incluso el valor por defecto "seatstorm"): Postgres solo
# aplica POSTGRES_PASSWORD al crear el PVC, y cambiarlo despues rompe la autenticacion de todos los servicios.
_ss_gen() { # VAR longitud
  local var="$1" len="$2" v
  if [ -z "${!var:-}" ] || _ss_placeholder "${!var}"; then
    v="$(_ss_cluster "$var" || true)"
    if [ -n "$v" ] && { ! _ss_placeholder "$v" || [ "$var" = DB_PASSWORD ]; }; then export "$var=$v"; echo "  $var: reutilizado del clúster"
    elif [ "$_ss_check" = 1 ]; then echo "SE GENERARA  $var"
    else export "$var=$(_ss_rand "$len")"; echo "  $var: generado aleatoriamente"; fi
  fi
}

echo "==> Variables para el despliegue en Kubernetes"
echo "    (los tokens se piden con entrada oculta y solo viven en esta terminal)"
echo
echo "Docker Hub (cuenta personal) — DOCKER_ID da nombre a las imagenes (docker.io/\$DOCKER_ID/<servicio>) y DOCKER_PAT permite descargarlas:"
echo "  PAT: https://app.docker.com/settings/personal-access-tokens (SOLO LECTURA basta para el clúster; nunca lo subas a git)"
_ss_dh_req=requerido; [ "${OVERLAY:-}" = local ] && _ss_dh_req=opcional
_ss_ask DOCKER_ID  "DOCKER_ID (tu usuario de Docker Hub)"        plain  "$_ss_dh_req"
_ss_ask DOCKER_PAT "DOCKER_PAT (personal access token)"           secret "$_ss_dh_req"
echo
echo "Asistente de chat (opcional; sin clave responde un texto de respaldo):"
_ss_ask ANTHROPIC_API_KEY "ANTHROPIC_API_KEY" secret opcional
_ss_ask NVIDIA_API_KEY    "NVIDIA_API_KEY (alternativa gratuita)" secret opcional
echo
echo "Arize AX — trazas del asistente (opcional; sin SPACE_ID y API_KEY no se exporta nada):"
echo "  Space ID y API key: Arize > Settings. Si pegaste la key en algun chat, rotala antes."
_ss_ask ARIZE_SPACE_ID  "ARIZE_SPACE_ID" plain opcional
if [ -n "${ARIZE_SPACE_ID:-}" ]; then
  _ss_ask ARIZE_API_KEY "ARIZE_API_KEY" secret requerido
  _ss_ask ARIZE_OTLP_ENDPOINT "ARIZE_OTLP_ENDPOINT" plain opcional "https://otlp.arize.com/v1"
  _ss_ask ARIZE_PROJECT_NAME  "ARIZE_PROJECT_NAME" plain opcional "seatstorm-assistant"
fi
echo
echo "Seguridad de la demo:"
_ss_gen DB_PASSWORD 24
_ss_gen ADMIN_TOKEN 32
_ss_gen SESSION_SECRET 48
_ss_gen TICKET_SECRET 32
if [ -z "${CONSOLE_PIN:-}" ] || [ "${CONSOLE_PIN:-}" = 4321 ]; then
  _pin="$(_ss_cluster CONSOLE_PIN || true)"
  [ -z "$_pin" ] && _pin="$(LC_ALL=C tr -dc '0-9' </dev/urandom | head -c 6 || true)"
  unset CONSOLE_PIN
  _ss_ask CONSOLE_PIN "PIN de la consola del presentador" plain requerido "$_pin"
fi

export DOCKER_ID DOCKER_PAT ANTHROPIC_API_KEY NVIDIA_API_KEY ARIZE_SPACE_ID ARIZE_API_KEY ARIZE_OTLP_ENDPOINT ARIZE_PROJECT_NAME DB_PASSWORD ADMIN_TOKEN SESSION_SECRET TICKET_SECRET CONSOLE_PIN
if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -n "${NVIDIA_API_KEY:-}" ]; then export LLM_PROVIDER="${LLM_PROVIDER:-nvidia}"; fi
echo
echo "==> Listo. PIN de la consola: ${CONSOLE_PIN:-?}"
unset _k _v _pin
