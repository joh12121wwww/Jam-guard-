# Validation record · 5 November 2026

## Automated checks

- Android debug APK compiled with Java 17, Gradle 8.9, AGP 8.7.3 and SDK 35.
- Android lint completed with zero errors. Remaining warnings concern deliberate synchronous preference persistence off the UI thread, bundled JavaScript required by the local interface, version-specific Android attributes and launcher icon guidance. No MissingPermission errors are suppressed.
- 21 executable Java protocol checks passed: current/legacy RF formats, alarm/clear/fault parsing, invalid and non-finite readings, fragmented lines and overflow recovery.
- Chromium mobile UI regression passed at 390×844: six pages, RF alarm/fault state, no horizontal overflow, HTML escaping of malicious device names/notes, device selection, incident review, map and export bridge calls, sensitivity command encoding and phone settings submission.
- Termux upload helper passed against a disposable local bare Git remote: first upload/push, repeat upload, paths with spaces, preservation of unrelated files, old source replacement, wrapper/workflow copying and refusal to overwrite uncommitted changes. No real GitHub repository was changed during testing.
- The ZIP includes a debug APK built from these sources, plus Gradle wrapper and reproducible GitHub Actions workflow. This is a test build, not a certified production release.

## Reproduce

Desktop/CI:

```bash
bash tests/run.sh
python3 tests/test_upload.py
bash ./gradlew --no-daemon clean assembleDebug lintDebug
```

Optional UI regression (Node.js):

```bash
npm install --no-save --prefix /tmp/jamguard-ui playwright@1.51.1
/tmp/jamguard-ui/node_modules/.bin/playwright install chromium
NODE_PATH=/tmp/jamguard-ui/node_modules node tests/ui.test.cjs
```

The UI test supplies isolated test state; there is no demo telemetry in the actual app and no automated Telegram send. Chromium UI testing does not substitute for Android WebView or physical device acceptance.

## Not physically validated

No ESP32/CC1101 was attached to this build environment. BLE pairing and protected settings writes, RF measurements/detection behaviour, phone siren audibility, Android power management, live GPS and actual Telegram delivery require the field checklist. Build success does not repair the currently reported CC1101 hardware/SPI fault.

## Implementation references

- Android background BLE: https://developer.android.com/develop/connectivity/bluetooth/ble/background
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Telegram Bot API: https://core.telegram.org/bots/api
- Included firmware integration contract: DEVICE_PROTOCOL.md
