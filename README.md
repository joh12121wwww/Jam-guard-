# JamGuard — EPS Global Track

Android BLE monitoring prototype with the original EPS Global Track logo, live RSSI display, alarm history and an on-phone siren.

## Build without Android Studio
1. Upload all files **preserving folder structure** to your GitHub repository.
2. Open **Actions → Build JamGuard APK → Run workflow** (or push to `main`).
3. When the build succeeds, open its run and download the `JamGuard-debug-apk` artifact.
4. Extract the ZIP artifact on your phone and install `app-debug.apk`.

**This ZIP is a source project, not a compiled APK.** GitHub Actions compiles the APK. Android may ask permission to install from your browser/files app.

## Connect to ESP32
- The app scans for a BLE advertisement whose name starts with `JamGuard`.
- The ESP32 must expose BLE service `c63c0001-92b0-4d7a-8ca8-13f57a6d0101` and notify characteristic `c63c0002-92b0-4d7a-8ca8-13f57a6d0101`.
- BLE notification payloads (UTF-8): `RSSI,-94`, `ALARM,-48`, `CLEAR,-91`.
- The firmware folder contains a **BLE bridge integration template**. It does **not** yet read the SX1278; merge the bridge into your working detector firmware.
- Your current ESP32 Bluetooth firmware is not automatically compatible until it uses this BLE protocol.

## Android notes
- Keep JamGuard running in the foreground service and grant Nearby Devices and Notifications permissions.
- On Blackview, disable aggressive battery optimisation for JamGuard and allow background activity.
- Phone volume, Do Not Disturb, Android restrictions and Bluetooth range can prevent an audible alarm. **Not a guaranteed life-safety or security alarm system.**
- Android 8+ notification channels may require changing notification settings after installation. The siren is played by the running app service, with a separate high-importance notification.
- Alarm history is local to the phone, not yet synchronised across users.

## Security and limitations
- This first prototype does not authenticate BLE devices or encrypt application payloads. Do not treat BLE names as trusted identities in a production deployment.
- A jammer may disrupt Bluetooth or radio reception. RSSI above a threshold is not proof of jamming.
- Test on the actual ESP32 and Blackview before operational use.
