#!/usr/bin/env bash
set -euo pipefail
: "${JARVIS_EMULATOR_SERIAL:?Set an isolated emulator transport}"
: "${JARVIS_DEVICE_HARNESS_OUTPUT:?Set the harness output directory}"
case "$JARVIS_EMULATOR_SERIAL" in emulator-*) ;; *) echo 'An isolated emulator is required'; exit 1;; esac
run_id="jarvis-$(date +%s)-$$"
log=$(mktemp)
trap 'rm -f "$log"' EXIT
adb -s "$JARVIS_EMULATOR_SERIAL" install -r "$JARVIS_DEVICE_HARNESS_OUTPUT/JarvisStorageHarness.apk" >/dev/null
adb -s "$JARVIS_EMULATOR_SERIAL" shell am force-stop im.talky.jarvis.storageharness
adb -s "$JARVIS_EMULATOR_SERIAL" shell am start -W -n im.talky.jarvis.storageharness/org.telegram.messenger.jarvis.StorageHarness --es run_id "$run_id" >/dev/null
for attempt in $(seq 1 30); do
    adb -s "$JARVIS_EMULATOR_SERIAL" logcat -d -s JarvisStorageHarness:I '*:S' > "$log"
    if rg -q "JARVIS_KEYSTORE_DEVICE_TEST_FAIL $run_id" "$log"; then echo 'Keystore device checks failed'; exit 1; fi
    if rg -q "JARVIS_KEYSTORE_DEVICE_TEST_PASS $run_id" "$log"; then echo 'Keystore device checks passed'; exit 0; fi
    sleep 1
done
echo 'Keystore device checks timed out'
exit 1
