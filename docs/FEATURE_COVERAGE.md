# Flyer and previously agreed feature coverage

| Requested capability | App location | Behaviour / required input |
|---|---|---|
| Connect and disconnect | Monitor / Devices | Bluetooth permissions, real detector advertising primary or recovered legacy service |
| Live 433.92 MHz RF | Monitor | Real RSSI from notifications; no synthetic data or swept spectrum claim |
| Phone siren control | Monitor / Health / notification | Local alarm audio, vibration, test and silence |
| Alarm history | History | SQLite records with time, RSSI and incident ID |
| Live monitoring | Monitor / Health | Selected nearby Bluetooth unit; one per phone |
| Incident locations | History / Control | Optional recent phone fix or configured fixed site; map link opens installed maps |
| Battery status | Health | Only if explicitly returned as battery_percent; otherwise unavailable |
| Last seen | Health / Monitor | Last recorded RF telemetry for the selected device, with elapsed age |
| Connection status | Header / Health | Actual subscription state; RF monitoring can fail even while Bluetooth stays connected |
| Firmware version | Health | Returned firmware field after authenticated settings read; unknown on older firmware |
| Telegram messages | Control / Settings | User bot + authorised chat + phone internet; sent only after explicit enable |
| Alarm details | Telegram and History | Device name, UTC time, RF, optional source/age/accuracy, incident ID |
| RF cleared notification | Settings / History | Detector-reported transition, user option |
| Control Room awareness | Telegram chat | Alerts from phone gateways in common chat; no invented backend or shared database |
| Automatic vehicle mode | Settings | Optional reconnect when app opens and retry during session; no ignition/motion detection |
| Background monitoring | Notification | User-started foreground service; Android termination/reboot requires restart |
| Adjustable sensitivity | Settings / Health | Pairing PIN, config TX subscription, authenticated RX writes, readback |
| Multiple JamGuard devices | Devices | Save/select up to 30, one active BLE link |
| Incident reports | History | Full database CSV; filtered displayed PDF via Android print |
| Review and acknowledgement | History | Local review note and reviewed flag; no operator-read claim in Telegram |
| Outdoor / handheld / vehicle profiles | Settings | Device name, location source and profile label; no fictitious hardware telemetry |
| Detector direct Wi-Fi gateway | Settings | Optional BLE configuration for compatible firmware; can be left off for phone-only operation |

No code for throttle control, vehicle immobilisation or physical jumper locking is included; these remain outside this app project.
