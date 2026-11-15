#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-debug}"
case "$mode" in
  debug) task=:TMessagesProj_App:assembleAfatDebug ;;
  release) task=:TMessagesProj_AppStandalone:assembleAfatStandalone ;;
  *) echo "Usage: scripts/build-android.sh [debug|release]" >&2; exit 2 ;;
esac
shift "$(( $# > 0 ? 1 : 0 ))"
# Development builds target one ABI; release retains the full architecture set.
if [[ "$mode" == debug ]]; then
    exec ./gradlew "$task" -PtalkyDevAbi="${TALKY_DEV_ABI:-arm64-v8a}" "$@"
fi
exec ./gradlew "$task" "$@"
