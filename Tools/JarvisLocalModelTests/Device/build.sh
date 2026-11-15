#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${JARVIS_DEVICE_HARNESS_OUTPUT:?Set an isolated test output directory}"
repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
build_dir="$JARVIS_DEVICE_HARNESS_OUTPUT"
mkdir -p "$build_dir/classes" "$build_dir/dex"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
tools="$ANDROID_HOME/build-tools/36.0.0"
cat > "$build_dir/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="im.talky.jarvis.storageharness">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
<application android:label="Jarvis Storage Test" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:allowBackup="false">
<activity android:name="org.telegram.messenger.jarvis.StorageHarness" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
</application></manifest>
MANIFEST
javac -Xlint:all -Werror -cp "$platform" -d "$build_dir/classes" "$repo_root/TMessagesProj/src/main/java/org/telegram/messenger/jarvis/JarvisLocalCredential.java" "$repo_root/TMessagesProj/src/main/java/org/telegram/messenger/jarvis/JarvisLocalKeyStore.java" "$repo_root/Tools/JarvisLocalModelTests/Device/StorageHarness.java"
"$tools/d8" --lib "$platform" --min-api 26 --output "$build_dir/dex" "$build_dir"/classes/org/telegram/messenger/jarvis/*.class
"$tools/aapt2" link -I "$platform" --manifest "$build_dir/AndroidManifest.xml" -o "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q -u "$build_dir/unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
if [[ ! -f "$build_dir/test.keystore" ]]; then
keytool -genkeypair -keystore "$build_dir/test.keystore" -storepass android -keypass android -alias test -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Isolated Jarvis Test' >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks "$build_dir/test.keystore" --ks-pass pass:android --out "$build_dir/JarvisStorageHarness.apk" "$build_dir/aligned.apk"
