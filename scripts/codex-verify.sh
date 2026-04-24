#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${ROOT_DIR}/.gradle-home}"

echo "[verify] Repo: ${ROOT_DIR}"
echo "[verify] Java version"
java -version

echo "[verify] Gradle test"
cd "${ROOT_DIR}"
if [ -x "./gradlew" ]; then
  GRADLE_USER_HOME="${GRADLE_USER_HOME}" ./gradlew test
else
  GRADLE_USER_HOME="${GRADLE_USER_HOME}" gradle test
fi

echo "[verify] Done"
