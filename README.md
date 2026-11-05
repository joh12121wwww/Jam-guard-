# JamGuard Mobile 2.0

EPS GLOBAL TRAC · Android 8–15+ source project · package `za.co.epservice.jamguard`

**No server needed.** The phone connects to one JamGuard detector over Bluetooth, stores incidents in SQLite and delivers alerts through your existing Telegram bot. Multiple phones can send to one authorised Telegram chat. The app does not claim to receive a shared fleet feed from Telegram.

## Upload using Termux

Download **JamGuard_Mobile.zip** to your phone’s **Download** folder. In Termux:

```bash
termux-setup-storage
pkg install git unzip
mkdir -p ~/jamguard-upload-v2
unzip -o ~/storage/downloads/JamGuard_Mobile.zip -d ~/jamguard-upload-v2
cd ~/jamguard-upload-v2/JamGuard_Mobile
bash UPLOAD_WITH_TERMUX.sh ~/Jam-guard-
```

The helper uses your previously configured GitHub SSH access to `joh12121wwww/Jam-guard-`. If the checkout is elsewhere, replace `~/Jam-guard-` with its path. It requires branch `main`, refuses uncommitted changes, pulls with fast-forward only, copies the new app sources, commits and pushes. It replaces `app/src` so obsolete Java classes do not clash; the old version remains in Git history. Run it from the extracted package **outside** the Git checkout. It includes hidden `.github` files automatically. No bot credentials belong in Git.

If Git asks for identity, set **your own** name/email with `git config --global user.name` and `git config --global user.email`, then rerun. If you see `Permission denied (publickey)`, your GitHub SSH key needs to be available in Termux as in the previous setup; the helper does not request or copy private keys.

### Get the APK

1. Open your GitHub repository → **Actions** → **Build JamGuard APK**.
2. Open the latest successful run → **Artifacts** → **JamGuard-debug-apk**.
3. Download and extract that artifact ZIP, then install `app-debug.apk`.
4. Grant Bluetooth and notification permissions. Grant location only if you want phone coordinates.

GitHub builds on an Ubuntu runner. Do **not** try to run the Android Gradle build inside Termux. The source ZIP is not an APK and cannot be installed directly.

### Existing installed app / signing

A debug APK can update an existing installation only when its signing certificate matches and the existing versionCode is no higher than 20. GitHub runners normally generate a new debug key, so you may see an incompatible-package error. **Export old incident records before uninstalling** an existing app. Uninstall deletes its local data. This source uses a new structured database and does not import the old free-text alarm history automatically.

For consistent future updates, supply a private debug signing keystore as the repository Actions secret `JAMGUARD_DEBUG_KEYSTORE_BASE64` (standard Android debug keystore, alias `androiddebugkey`, password `android`). The workflow restores it privately before building. Never commit a signing keystore or its encoded content. Production distribution needs a dedicated release key, release process and device testing. Changing to a new signing key cannot update an older installation signed with another key.

## First use

1. Devices → **Scan nearby** → **Select detector** → Monitor → **Connect detector**.
2. Settings → give the unit a useful name, select vehicle / handheld / fixed-site profile, save.
3. Settings → paste your bot token and authorised chat ID, enable Telegram, save.
4. Control → **Send test message**. Confirm the event changes from `queued` to `sent` and appears in Telegram.
5. Health → test the phone siren. Adjust your phone alarm/media volume as necessary.
6. For detector settings: Health → **Pair / load detector settings**. Enter the six-digit PIN from the detector USB boot log. Then save sensitivity and verify the returned values in Health.

Private Telegram chat: start a conversation with the bot first. Group: add your bot and allow it to send messages. Use your numeric chat ID, a negative supergroup ID or a valid `@channelusername`. Telegram does not connect to Bluetooth itself: the phone supplies the internet gateway.

## Included features

| Feature | Implementation |
|---|---|
| RF dashboard | Real detector RSSI, 433.92 MHz label, 240-sample in-memory buffer, last 120 readings plotted, threshold guide, stale-telemetry detection |
| Smart jamming alerts | Detector alarm transitions, deduplication of repeated alarm samples, phone siren, vibration, notification silence action, RF-cleared events |
| Telegram Control Room | Phone gateway, durable outbox, accepted/pending/failed status, timed retries, map links, manual test, one chat shared across phones |
| Automatic vehicle mode | Reconnect selected detector during a running session, optional automatic session when app opens; no ignition sensing |
| Jam Guard Device Health | Actual connection, RF fault, last telemetry, settings channel, firmware/battery when supplied; unknown fields remain unknown |
| History and reports | Search/filter, local review notes, acknowledgements, CSV of all records, Android Print / Save as PDF for filtered on-screen records |
| Background monitoring | Foreground connected-device service and persistent notification; optional location service launched from visible app |
| Adjustable sensitivity | Authenticated BLE settings, 20-byte acknowledged chunks, newline framing, request IDs, timeout and GET verification after SET |
| Multiple detectors | Up to 30 saved BLE address/name profiles, explicit selection, one active monitored detector per phone |
| Remote notifications | Telegram alarm, RF clear, lost connection, stale RF and radio fault records; no server account required |
| Device gateway settings | Optional direct detector Wi-Fi / Telegram configuration for firmware implementing the included protocol |
| Privacy and secrets | App-private database, Keystore-encrypted phone bot token, no analytics, no remote web content, no bot token in UI snapshots |

## Important boundaries

- This is an **RF strength-over-time graph**, not a swept frequency spectrum or direction finder. It does not prove malicious jamming or successful locking.
- Phone gateway monitoring requires Bluetooth within range. Internet is needed for Telegram; incident storage continues offline.
- Saved profiles are not simultaneous remote fleet monitoring. Telegram is the shared notification destination; no backend, incoming bot commands, remote relay control or live shared device map is included.
- RF alarms originate from the detector firmware. Changing a phone profile does not change detector hardware, range, battery or radio configuration.
- Battery and firmware cannot be invented. The current CC1101 telemetry contract does not guarantee those fields; the app shows “Not reported by firmware.”
- `sent` means Telegram accepted the message and returned a message ID, not that an operator read it. Local review notes stay local. Timeouts can lead to duplicate Telegram messages; each includes a persistent incident ID.
- Pending messages retain the original chat and bot identity. Changing the chat applies to new incidents. A replacement token for the same bot can deliver old messages; selecting a different bot marks incompatible pending events failed. Restore the original bot and retry if needed.
- The service processes the outbox every five seconds while running. It does not send after force-stop or device reboot until you reopen/start the app. Stopping monitoring stops the gateway too.
- GPS is optional. Only recent (≤120 seconds) fixes are attached, with age and accuracy. No fix means “location unavailable.” Fixed-site coordinates are configured by the user.
- Android can end a session after force-stop, reboot or power restrictions. Reopen and connect to resume. Auto reconnect is not an ignition or movement sensor.
- Phone siren auto-stops after 60 seconds. RF alarm and history remain active; silence does not clear a detector alarm.
- Original ESP32 and ESP32-C6 both work at the **BLE protocol** level if running compatible firmware. This app cannot fix a CC1101 SPI/wiring fault (`ERR,RADIO` or registers all zero). No firmware or wiring changes are bundled.
- BLE telemetry is supported on primary and recovered legacy UUIDs. Protected settings require the config RX/TX characteristics and bonded PIN authentication. See `docs/DEVICE_PROTOCOL.md`.

## Build and verification

Java 17, Android SDK 35, Android Gradle Plugin 8.7.3, Gradle 8.9. No Firebase, server credentials, Google API key or third-party Android runtime dependencies.

On a desktop/CI with Android SDK:

```bash
bash tests/run.sh
./gradlew --no-daemon assembleDebug lintDebug
```

`tests/run.sh` needs Java 17 and Node.js. The optional browser regression suite in `tests/ui.test.cjs` needs Playwright; see `docs/VALIDATION.md`. Physical BLE pairing, siren, background survival, actual GPS and real Telegram delivery must be exercised on your phone with the detector. No automated test sends a Telegram message.

See `docs/ARCHITECTURE.md`, `docs/FEATURE_COVERAGE.md`, `docs/VALIDATION.md` and `docs/FIELD_CHECKLIST.md` for implementation and acceptance details.
