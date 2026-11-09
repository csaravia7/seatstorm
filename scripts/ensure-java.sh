#!/usr/bin/env bash
# Sourced by other scripts: guarantees a valid JAVA_HOME with JDK >= 21 (fixes Codespaces with a stale/unset JAVA_HOME).
_java_ok() { [[ -x "$1/bin/java" && -x "$1/bin/javac" ]] && [[ "$("$1/bin/java" -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')" -ge 21 ]]; }

if [[ -n "${JAVA_HOME:-}" ]] && ! _java_ok "$JAVA_HOME"; then
  echo ">> JAVA_HOME ($JAVA_HOME) is not a valid JDK 21+; searching for one..." >&2
  unset JAVA_HOME
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  candidates=()
  if command -v java >/dev/null 2>&1; then
    candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")")
  fi
  candidates+=(/usr/local/sdkman/candidates/java/current "$HOME"/.sdkman/candidates/java/current)
  for d in /usr/lib/jvm/*; do candidates+=("$d"); done
  [[ -x /usr/libexec/java_home ]] && candidates+=("$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)")
  for c in "${candidates[@]}"; do
    if [[ -n "$c" ]] && _java_ok "$c"; then export JAVA_HOME="$c"; break; fi
  done
fi

if [[ -z "${JAVA_HOME:-}" ]] && command -v apk >/dev/null 2>&1; then
  echo ">> No JDK 21 found; trying apk (openjdk21-jdk)..." >&2
  if sudo apk add --no-cache openjdk21-jdk >&2; then
    for c in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/default-jvm /usr/lib/jvm/*21*; do
      if _java_ok "$c"; then export JAVA_HOME="$c"; break; fi
    done
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]] && command -v apt-get >/dev/null 2>&1; then
  echo ">> No JDK 21 found; trying apt (openjdk-21-jdk-headless)..." >&2
  if { sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-21-jdk-headless; } >&2; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  # Universal fallback: download Temurin 21 (any Linux, no root needed)
  case "$(uname -m)" in x86_64|amd64) arch=x64;; aarch64|arm64) arch=aarch64;; *) echo "ERROR: unsupported arch $(uname -m)" >&2; return 1 2>/dev/null || exit 1;; esac
  os=linux; [[ -f /etc/alpine-release ]] && os=alpine-linux
  dest="$HOME/.local/jdk-21"
  if ! _java_ok "$dest"; then
    echo ">> Downloading Temurin 21 ($arch) to $dest ..." >&2
    mkdir -p "$dest" && curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse" | tar -xz -C "$dest" --strip-components=1
  fi
  if _java_ok "$dest"; then export JAVA_HOME="$dest"; else
    echo "ERROR: could not obtain a JDK 21. Install Temurin 21 manually and set JAVA_HOME." >&2; return 1 2>/dev/null || exit 1
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo ">> Using JAVA_HOME=$JAVA_HOME" >&2
