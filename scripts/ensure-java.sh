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

if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v apt-get >/dev/null 2>&1; then
    echo ">> No JDK 21 found; installing openjdk-21-jdk-headless via apt..." >&2
    sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-21-jdk-headless >&2
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  else
    echo "ERROR: JDK 21+ not found. Install Temurin 21 and set JAVA_HOME." >&2; return 1 2>/dev/null || exit 1
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo ">> Using JAVA_HOME=$JAVA_HOME" >&2
