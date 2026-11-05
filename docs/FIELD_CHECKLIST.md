# Phone and hardware acceptance

Complete these on the actual phone before relying on monitoring. Software tests do not verify RF electronics.

1. Install APK, permit Bluetooth/notifications and select the correct physical detector address. Confirm no automatic connection to a different nearby unit.
2. With a working radio, check RF updates against the serial log. On an RF fault the app must show unavailable, never a fabricated baseline.
3. Use an ordinary authorised remote to exercise receiver activity and the firmware’s alarm duration. Check one record and one queued Telegram message per alarm transition, with the expected RF reading.
4. Check the phone siren and vibration at the volume you use. Silence it from app and notification; the incident and alarm state must remain. Test automatic 60-second siren cutoff.
5. Let the detector report RF clear. Confirm the clear record/Telegram message when enabled. Disconnect during alarm and verify the app records interruption rather than a false RF clearance.
6. Turn phone internet off during an incident. Verify a local queued event. Restore internet while monitoring runs and confirm Telegram acceptance plus matching incident ID. Test invalid chat/token and retry after correction.
7. Switch Telegram destination with pending events and verify old events retain the old destination. Rotate to a new token for the same bot if necessary. Never post your token in a screenshot or GitHub.
8. Enable GPS during a visible monitoring session. Check the coordinate source, age and accuracy; test approximate permission and unavailable GPS. With no recent fix, incident location must be unavailable.
9. Choose fixed-site profile, configure latitude/longitude and verify those coordinates in a new incident and map link.
10. Lock the phone, leave the app and test alarms and Telegram after 15 minutes. Repeat on battery saver. Reopen after force-stop/reboot; check it clearly resumes a new session instead of pretending the gap was monitored.
11. Pair with boot PIN, load config, change threshold and delay, inspect matching success and GET readback, reboot detector and verify saved values. Test a telemetry-only firmware unit; settings must report unavailable.
12. Export CSV, search/filter history, save a PDF through Android print, add a review note and verify records survive app restart.
13. If using direct detector Wi-Fi/Telegram, test separately and inspect returned status. A queued detector test is not delivery confirmation. Keep one active alert route to avoid duplicates.

Known bench issue from the project: CC1101 PARTNUM/VERSION both zero is a hardware/SPI communication problem. A phone app cannot repair it.
