# JamGuard CC1101 Config v2 — app integration contract

Target: original ESP32; Arduino-ESP32 2.0.17.

## GATT

Advertised name **JamGuard**, advertised primary service `6c8e0001-5cd0-4f6b-9d5d-b31c8803a001`. Name is in scan response, so perform an active BLE scan.

| Purpose | UUID | Properties / access |
|---|---|---|
| RF telemetry | `6c8e0002-5cd0-4f6b-9d5d-b31c8803a001` | READ + NOTIFY; prior format retained |
| Config command RX | `6c8e0003-5cd0-4f6b-9d5d-b31c8803a001` | WRITE with response; encrypted, MITM-authenticated pairing required |
| Config reply TX | `6c8e0004-5cd0-4f6b-9d5d-b31c8803a001` | READ + NOTIFY; encrypted, MITM-authenticated pairing; protected CCCD |

The original service `c63c0001-92b0-4d7a-8ca8-13f57a6d0101` and TX `c63c0002-92b0-4d7a-8ca8-13f57a6d0101` also remain, using integer `RSSI`, `ALARM`, `CLEAR`. Primary telemetry uses `R,-94.5,0/1` or `ERR,RADIO`. Do not send settings replies through either telemetry characteristic.

### Configuration sequence

1. Connect and discover primary service. Pair/bond using the six-digit PIN printed at boot in USB Serial Monitor. Trigger/read protected config access, handling Android's authentication/bond retry flow.
2. Enable notifications on **config TX**, writing its CCCD only after pairing/authentication succeeds. Serialize GATT operations and wait for each descriptor/write callback.
3. Encode one complete JSON object as UTF-8, append LF (`0x0A`) and send chunks of **at most 20 bytes** to config RX with acknowledged writes. Wait for `onCharacteristicWrite` success before the next chunk; no fixed inter-chunk sleep is a substitute for the callback.
4. Reassemble config TX notification bytes across chunks until LF; parse that completed JSON response. A JSON token may span notifications. Never parse one chunk as one complete response.
5. Send only one logical command at a time. Match its unsigned `id` in the response; do not treat radio telemetry as an acknowledgement. Responses expire after 10 seconds if unread/unsubscribed. The READ value is just the most recent fragment/initial READY, not a complete replacement for subscribed replies.
6. GET after a successful SET verifies current saved values; GET status after a queued Telegram test checks send state. `queued` does not mean delivered.

Maximum command length **767 bytes** excluding LF. No embedded NUL; CR line endings are ignored. Overlong BLE frames are discarded through the next LF. Queue-full/oversize BLE frames may produce no acknowledgement: timeout, then send GET/retry deliberately. Do not blindly retry SET/test in a rapid loop. Disconnect invalidates queued commands/fragments from that connection. Response framing/chunking needs implementation in the Android app, separate from its existing RF parser.

## Commands

| `cmd` | Fields |
|---|---|
| `get` or `status` | optional unsigned `id`; read settings/status without secrets |
| `set` | optional `id`; any nonempty subset of fields below, validated atomically |
| `test_telegram` | optional `id`; requires enabled Telegram; returns queue/event ID |
| `defaults` | optional `id`; resets settings/clears credentials; retains pairing PIN/bonds |

SET fields:

| Key | Type / range |
|---|---|
| `threshold` | JSON integer −120 through −20, dBm |
| `delay_seconds` | JSON integer 1 through 30 |
| `wifi_enabled` | JSON boolean |
| `wifi_ssid` | JSON UTF-8 string, at most 32 bytes; required when Wi-Fi enabled |
| `wifi_pass` | JSON string; empty for open Wi-Fi, 8–63 printable ASCII chars, or 64 hex characters |
| `telegram_enabled` | JSON boolean; requires Wi-Fi enabled and token/chat present |
| `telegram_token` | JSON string, at most 128 bytes, numeric bot ID + colon + token |
| `telegram_chat` | JSON string, signed numeric chat ID or supported `@channelusername`, at most 32 bytes |
| `notify_clear` | JSON boolean |

Unknown SET fields and wrong JSON types are rejected. Omitted fields keep their saved values. Empty token/chat clears that value, requiring Telegram disabled in the same command if previously enabled. Enabling Telegram with incomplete dependencies is rejected; perform a complete atomic SET or save credentials while Telegram remains off.

Success example: `{"id":1,"ok":true,"saved":true,...}`. Failure example: `{"id":1,"ok":false,"error":"delay_range"}`. Replies contain no `wifi_pass`, `telegram_token` or pairing PIN. Presence flags are `wifi_pass_set` and `telegram_token_set`.

The config channel intentionally requires stronger security than old telemetry. Do not remove the GATT permissions to make an old APK appear compatible. Update the app to handle bonding; USB Serial accepts the same newline JSON as a local setup route.
