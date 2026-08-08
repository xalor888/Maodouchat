#!/usr/bin/env bash
# Pin JAVA_HOME to a JDK 21 runtime for Maodouchat Gradle builds.
# Usage (Git Bash): source scripts/use-jdk21.sh
# Do not use system JDK 25 — Kotlin DSL fails with IllegalArgumentException: 25.0.2

set -euo pipefail

candidates=(
  "${JAVA_HOME_21:-}"
  "/c/Program Files/Android/Android Studio1/jbr"
  "/c/Program Files/Android/Android Studio/jbr"
  "/c/Program Files/Java/jdk-21"
  "/c/Program Files/Eclipse Adoptium/jdk-21"*
  "/c/Program Files/Microsoft/jdk-21"*
  "$HOME/.jdks/jbr-21"*
  "$HOME/.jdks/jdk-21"*
)

pick=""
for c in "${candidates[@]}"; do
  [[ -z "$c" ]] && continue
  # Expand globs
  for path in $c; do
    if [[ -x "$path/bin/java" ]]; then
      ver=$("$path/bin/java" -version 2>&1 | head -n1 || true)
      if echo "$ver" | grep -qE 'version "21'; then
        pick="$path"
        break 2
      fi
    fi
  done
done

if [[ -z "$pick" ]]; then
  echo "use-jdk21.sh: no JDK 21 found. Install Android Studio JBR 21 or set JAVA_HOME_21." >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="$pick"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
java -version
