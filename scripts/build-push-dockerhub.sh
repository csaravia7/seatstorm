#!/usr/bin/env bash
# build-push-dockerhub.sh — builds the images used on AKS (11: all, or only the services you pick) and pushes them to YOUR Docker Hub.
#
#   scripts/build-push-dockerhub.sh                       # guided: asks for everything it needs
#   scripts/build-push-dockerhub.sh --native              # GraalVM native images (recommended for AKS)
#   scripts/build-push-dockerhub.sh --jvm                 # JVM images (faster to build; deploy with OVERLAY=aks-jvm)
#   scripts/build-push-dockerhub.sh --native --tag v1.0.0 # a specific version instead of "latest"
#   scripts/build-push-dockerhub.sh --services order-service,bff   # only these services (no question)
#   scripts/build-push-dockerhub.sh --all                 # all 11 services (no question)
#   scripts/build-push-dockerhub.sh --yes                 # do not ask for the final confirmation
#   scripts/build-push-dockerhub.sh --jvm --dry-run       # build only: no Docker Hub login, nothing is pushed
#
# Services (7 Quarkus: event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification;
# others: assistant-agent bff bot-swarm web). Without --services/--all it asks: all, or service by service.
# It asks (hidden input for tokens, nothing is written to disk):
#   DOCKER_ID / DOCKER_PAT         Docker Hub user / access token with write access
#   DT_ENV_URL / DT_API_TOKEN      Dynatrace (native only, optional: press Enter to skip)
# Any of these can be exported beforehand to skip the question.
#   PLATFORMS                      target architecture, default linux/amd64 (AKS x86 nodes); linux/arm64 for arm nodes
# Next step:  make k8s-deploy   (JVM images: OVERLAY=aks-jvm make k8s-deploy)
set -euo pipefail
cd "$(dirname "$0")/.."

QUARKUS=(event-catalog order-service seat-inventory payment-sim ticketing seatmap-query notification)
ALL_SVCS=("${QUARKUS[@]}" assistant-agent bff bot-swarm web)
MODE=""; ASSUME_YES=""; TAG="${TAG:-}"; DRY_RUN="${DRY_RUN:-}"
SELECTED="${SEATSTORM_SERVICES:-}"; CHOSEN=""; [ -z "$SELECTED" ] || CHOSEN=1   # SELECTED: comma-separated, empty = all
while [ $# -gt 0 ]; do
  case "$1" in
    --native) MODE=native;;
    --jvm)    MODE=jvm;;
    --tag)    TAG="${2:?--tag needs a value}"; shift;;
    --services) SELECTED="${2:?--services needs a comma-separated list}"; CHOSEN=1; shift;;
    --all)    SELECTED=""; CHOSEN=1;;
    --yes|-y) ASSUME_YES=1;;
    --dry-run) DRY_RUN=1;;
    -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2;;
  esac
  shift
done
PLATFORMS="${PLATFORMS:-linux/amd64}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ask() { # ask VAR "prompt" [default]
  local var="$1" prompt="$2" def="${3:-}" val
  [ -n "${!var:-}" ] && return 0
  [ -t 0 ] || { echo "ERROR: $var is required (export it, or run in a terminal)" >&2; exit 2; }
  read -rp "$prompt${def:+ [$def]}: " val; val="${val:-$def}"
  [ -n "$val" ] || { echo "$var must not be empty" >&2; exit 2; }
  export "$var=$val"
}
ask_secret() { # ask_secret VAR "prompt"
  local var="$1" prompt="$2" val
  [ -n "${!var:-}" ] && return 0
  [ -t 0 ] || { echo "ERROR: $var is required (export it, or run in a terminal)" >&2; exit 2; }
  read -rsp "$prompt (hidden): " val; echo
  [ -n "$val" ] || { echo "$var must not be empty" >&2; exit 2; }
  export "$var=$val"
}

say "SeatStorm — build and push the images to Docker Hub"
command -v docker >/dev/null 2>&1 || { echo "ERROR: Docker is not installed" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "ERROR: the Docker daemon is not running (macOS with Colima: colima start --cpu 4 --memory 8)" >&2; exit 1; }

host="$(uname -m)"; host="${host/x86_64/amd64}"; host="${host/aarch64/arm64}"
case "$PLATFORMS" in *"$host"*) DEFAULT_CHOICE=1;; *) DEFAULT_CHOICE=2;; esac   # cross-arch native builds are impractical

# 1) Native or JVM
if [ -z "$MODE" ]; then
  echo "Image type:"
  echo "  1) Native (GraalVM)  — recommended for AKS: tiny, starts in ~0.1 s. ~4 min per service, needs ~8 GB RAM for Docker, build on a $PLATFORMS machine."
  echo "  2) JVM               — fast to build, any machine; uses more memory (deploy with OVERLAY=aks-jvm)."
  [ -t 0 ] || { echo "ERROR: pass --native or --jvm" >&2; exit 2; }
  read -rp "Choose [$DEFAULT_CHOICE]: " c; case "${c:-$DEFAULT_CHOICE}" in 2) MODE=jvm;; *) MODE=native;; esac
fi

# 2) Services: all, or service by service
for s in ${SELECTED//,/ }; do
  case " ${ALL_SVCS[*]} " in *" $s "*) ;; *) echo "ERROR: unknown service '$s' (valid: ${ALL_SVCS[*]})" >&2; exit 2;; esac
done
if [ -z "$CHOSEN" ] && [ -t 0 ]; then
  echo
  echo "Services to build:"
  echo "  1) All ${#ALL_SVCS[@]} services"
  echo "  2) Choose service by service"
  read -rp "Choose [1]: " c
  if [ "${c:-1}" = 2 ]; then
    PICKED=()
    for s in "${ALL_SVCS[@]}"; do
      read -rp "  Build $s? [Y/n] " a
      case "${a:-Y}" in n|N) ;; *) PICKED+=("$s");; esac
    done
    [ ${#PICKED[@]} -gt 0 ] || { echo "No service selected."; exit 1; }
    SELECTED="$(IFS=,; echo "${PICKED[*]}")"
  fi
fi
SEL=(); if [ -n "$SELECTED" ]; then SEL=(${SELECTED//,/ }); else SEL=("${ALL_SVCS[@]}"); fi
[ ${#SEL[@]} -lt ${#ALL_SVCS[@]} ] || SELECTED=""   # everything selected = default
HAS_QUARKUS=""; for s in "${SEL[@]}"; do case " ${QUARKUS[*]} " in *" $s "*) HAS_QUARKUS=1;; esac; done

# 3) Tag
[ -n "$TAG" ] || { if [ -t 0 ]; then read -rp "Image tag [latest]: " TAG; fi; TAG="${TAG:-latest}"; }

# 4) Docker Hub credentials
if [ -z "$DRY_RUN" ]; then
  say "Docker Hub (hub.docker.com/settings/security → New access token, with Read & Write)"
  ask DOCKER_ID "  DOCKER_ID (Docker Hub username)"
  ask_secret DOCKER_PAT "  DOCKER_PAT (access token)"
else
  DOCKER_ID="${DOCKER_ID:-seatstorm}"
fi

# 5) Dynatrace (native only, and only if a Quarkus service is built)
if [ "$MODE" = native ] && [ -n "$HAS_QUARKUS" ]; then
  if [ -z "${DT_ENV_URL:-}${DT_API_TOKEN:-}" ] && [ -z "${DT_SKIP:-}" ]; then
    say "Dynatrace native module (optional) — press Enter to build without it"
    if [ -t 0 ]; then
      read -rp "  DT_ENV_URL (e.g. https://abc12345.live.dynatrace.com): " DT_ENV_URL || true
      if [ -n "${DT_ENV_URL:-}" ]; then ask_secret DT_API_TOKEN "  DT_API_TOKEN (scope InstallerDownload)"; else export DT_SKIP=1; fi
    else
      export DT_SKIP=1
    fi
  fi
  [ -z "${DT_ENV_URL:-}" ] || export DT_ENV_URL DT_API_TOKEN
fi

# 6) Architecture check (AKS nodes are linux/amd64 by default)
if [ "$MODE" = native ] && [ -n "$HAS_QUARKUS" ]; then
  case "$PLATFORMS" in *"$host"*) ;; *)
    echo
    echo "WARNING: this machine is $host but the images target $PLATFORMS. Native builds under emulation are extremely slow and often fail."
    echo "         Recommended: run this script in the GitHub Codespace (amd64), or choose --jvm."
    if [ -z "$ASSUME_YES" ] && [ -t 0 ]; then read -rp "Continue anyway? [y/N] " a; case "$a" in y|Y|s|S) ;; *) echo "Cancelled."; exit 1;; esac; fi;;
  esac
fi

say "Summary"
echo "  Type:      $MODE ($PLATFORMS)"
echo "  Images:    docker.io/$DOCKER_ID/<service>:$TAG   (${#SEL[@]} of ${#ALL_SVCS[@]})"
echo "  Services:  ${SEL[*]}"
[ "$MODE" = native ] && [ -n "$HAS_QUARKUS" ] && echo "  Dynatrace: $([ -n "${DT_ENV_URL:-}" ] && echo "$DT_ENV_URL" || echo 'no')"
[ -z "$DRY_RUN" ] || echo "  DRY RUN:   build only, nothing is pushed"
if [ -z "$ASSUME_YES" ] && [ -t 0 ]; then
  action="Build and push"; [ -z "$DRY_RUN" ] || action="Build (dry run)"
  read -rp "$action now? [Y/n] " a; case "${a:-Y}" in n|N) echo "Cancelled."; exit 1;; esac
fi

export DOCKER_ID TAG PLATFORMS DRY_RUN SEATSTORM_SERVICES="$SELECTED"
if [ -z "$DRY_RUN" ]; then export DOCKER_PAT; fi
if [ "$MODE" = native ]; then
  scripts/push-native.sh
  NEXT="make k8s-deploy"
else
  scripts/push-dockerhub.sh
  NEXT="OVERLAY=aks-jvm make k8s-deploy"
fi

if [ -n "$DRY_RUN" ]; then say "Dry run done ✔  nothing was pushed"; exit 0; fi
say "Done ✔  Pushed to Docker Hub (tag $TAG):"
for s in "${SEL[@]}"; do echo "  docker.io/$DOCKER_ID/$s:$TAG"; done
echo "Next: deploy to AKS →  $NEXT$([ "$TAG" = latest ] || echo " TAG=$TAG")"
