# Architecture

The native Java Android service owns BLE, alarm audio, foreground notification, optional location, SQLite and Telegram HTTP. A bundled HTML/CSS/JS renderer provides the interface through a narrowly scoped JavaScript bridge. It never loads remote content, and navigation/network requests from the WebView are blocked. Map links open a separate installed maps app. User/device text is HTML-escaped.

## Data path

Detector → BLE notification → strict RF parser → live state + alarm transition → SQLite incident + captured destination → serial Telegram outbox → HTTPS sendMessage → message ID stored.

The app records an incident before attempting internet delivery. Repeated alarm samples do not create repeated alarms. A clear requires a detector-reported clear transition. Connection loss is an interruption, never an inferred RF clear. No RF updates for 15 seconds creates a telemetry loss event and hides the stale RSSI. Radio error messages hide the reading and create a fault event on transition.

The last-seen timestamp is retained per detector across sessions. The live signal buffer is bounded and in memory. The database is durable and contains incident state, locations, local review notes, delivery status, retry counters and Telegram message IDs. The UI reads the newest 1,000 incidents; CSV exports all. Uninstall removes the database. Existing v0.x plain-text history remains outside this new database; export before replacing the old installation if needed.

## BLE settings

All BLE callback processing is marshalled to the main handler. GATT descriptor writes and characteristic writes are serialised. Configuration waits for bonding, subscribes to config TX, writes acknowledged 20-byte chunks and waits for a matching JSON request ID. Config bytes are accumulated to LF before UTF-8 decoding. A setting success triggers GET to read saved values. Oversized, malformed or expired responses do not become successful saves. A command timeout does not trigger a blind SET retry.

## Telegram

The single network consumer handles one ready event per five-second interval. HTTP timeouts and temporary errors use bounded exponential backoff; HTTP 429 respects retry_after. Permanent HTTP 4xx failures remain failed until the operator retries after correcting configuration. An ambiguous timeout can result in a repeated send; Telegram sendMessage does not supply an app-controlled idempotency key, so incident IDs are included for operator correlation.

The destination chat and bot numeric identity are captured with the event. Queued alerts are not redirected to a newly entered chat. Bot tokens are encrypted using an AES/GCM key in Android Keystore, excluded from backup and never copied to WebView snapshots. A different bot cannot send the original pending batch; restoring the correct bot and retrying allows recovery.

No getUpdates polling, webhook, incoming command execution, relay operation, immobilisation, database server, Firebase or external analytics is included. Multiple phone gateways can write to the same Telegram chat without consuming each other's inbound bot updates.

## Background and locations

Monitoring starts from the visible activity with connectedDevice foreground service type. GPS, when opted in and granted, adds location foreground type while the app is visible and starts location listeners. No background-location permission or reboot autostart is requested. The service is START_NOT_STICKY to avoid claiming a restarted session is monitoring without re-establishing its permissions and state. Android/app force-stop requires reopening.

Vehicle and handheld events use only fixes no older than 120 seconds. Fixed-site events use configured coordinates. Neither uses BLE RSSI as distance or location. Device health values are shown only from actual known fields or returned status.

## Signing and production

The workflow makes a debug APK for testing. A private stable debug keystore can be supplied through GitHub Actions secrets. Production needs a controlled release signing key, store/distribution review and physical end-to-end acceptance. Bot tokens on a phone are administrator credentials for that bot; use a dedicated monitoring bot and rotate compromised tokens through Telegram. There is no claimed certification, guaranteed detection radius or availability SLA.
