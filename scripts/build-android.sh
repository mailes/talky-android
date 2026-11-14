#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-debug}"
case "$mode" in
  debug) task=:TMessagesProj_AppStandalone:assembleAfatDebug ;;
  release) task=:TMessagesProj_AppStandalone:assembleAfatStandalone ;;
  *) echo "Usage: scripts/build-android.sh [debug|release]" >&2; exit 2 ;;
esac
shift "$(( $# > 0 ? 1 : 0 ))"
exec ./gradlew "$task" "$@"
