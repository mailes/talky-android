#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${JARVIS_JSON_JAR:?Set JARVIS_JSON_JAR to an org.json test-runtime jar}"
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
source_dir="$repo_root/TMessagesProj/src/main/java/org/telegram/messenger/jarvis"
javac -Xlint:all -Werror -cp "$JARVIS_JSON_JAR:$ANDROID_HOME/platforms/android-36/android.jar" -d "$test_dir" "$source_dir"/*.java "$repo_root/Tools/JarvisLocalModelTests/JarvisLocalModelTests.java"
java -ea -cp "$test_dir:$JARVIS_JSON_JAR" org.telegram.messenger.jarvis.JarvisLocalModelTests

# Optional native signature checks; no provider or Talky network calls are made.
if [[ -n "${JARVIS_OPENSSL_PREFIX:-}" ]]; then
    native_arch=()
    if [[ -n "${JARVIS_NATIVE_TEST_ARCH:-}" ]]; then native_arch=(-arch "$JARVIS_NATIVE_TEST_ARCH"); fi
    c++ "${native_arch[@]}" -std=c++17 -Wall -Wextra -Werror -I "$repo_root/TMessagesProj/jni" -I "$JARVIS_OPENSSL_PREFIX/include" "$repo_root/Tools/JarvisLocalModelTests/ticket.cpp" -L "$JARVIS_OPENSSL_PREFIX/lib" -lcrypto -o "$test_dir/ticket"
    "$test_dir/ticket"
fi
