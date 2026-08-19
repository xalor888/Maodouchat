#!/usr/bin/env bash
# Pin JAVA_HOME to a JDK 21 runtime for Maodouchat Gradle builds.
# Usage (Git Bash): source scripts/use-jdk21.sh
# Do not use system JDK 25 — Kotlin DSL fails with IllegalArgumentException: 25.0.2

set -euo pipefail

candidates=(
  "${JAVA_HOME_21:-}"
  "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"
  "/Library/Java/JavaVirtualMachines/temurin-21"*/Contents/Home
  "/Library/Java/JavaVirtualMachines/jdk-21"*/Contents/Home
  "/c/Program Files/Android/Android Studio1/jbr"
  "/c/Program Files/Android/Android Studio/jbr"
  "/c/Program Files/Java/jdk-21"
  "/c/Program Files/Eclipse Adoptium/jdk-21"*
  "/c/Program Files/Microsoft/jdk-21"*
  "$HOME/.jdks/jbr-21"*
  "$HOME/.jdks/jdk-21"*
)

pick=""
check_jdk() {
  local candidate_path="$1"
  [[ -z "$candidate_path" || ! -x "$candidate_path/bin/java" ]] && return 1
  local ver
  ver=$("$candidate_path/bin/java" -version 2>&1 | head -n1 || true)
  if echo "$ver" | grep -qE 'version "21'; then
    pick="$candidate_path"
    return 0
  fi
  return 1
}

for candidate in "${candidates[@]}"; do
  [[ -z "$candidate" ]] && continue
  if [[ "$candidate" == *\** ]]; then
    while IFS= read -r path; do
      if check_jdk "$path"; then
        break 2
      fi
    done < <(compgen -G "$candidate" 2>/dev/null || true)
  else
    if check_jdk "$candidate"; then
      break
    fi
  fi
done

if [[ -z "$pick" ]]; then
  echo "use-jdk21.sh: no JDK 21 found. Install Android Studio JBR 21 or set JAVA_HOME_21." >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="$pick"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
java -version
