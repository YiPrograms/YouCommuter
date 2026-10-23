# Bluetooth borrowing verification — 2026-10-21

The owner's latest sample was an ordinary bike. The implemented product rule treats a new Bluetooth ACL connection with a cached name starting with `EYB` as successful borrowing during a commute awaiting a bike. It uses the selected borrow station, leaves bike-type preferences unchanged, and never interprets disconnection as a return. Missing/unrelated names and already-connected snapshots do not trigger borrowing. Returns remain manual.

## Implementation

- `YouBikeBorrowDetection` keeps the prefix and phase rule in `core:domain`.
- `BluetoothBorrowService` in `core:platform` observes connections without scanning, opening GATT or discovering services. The app supplies only the local session repository.
- The resumed activity requests Nearby devices and starts the foreground observer. A Bluetooth-ready event can retry startup while the activity is resumed. Denied permission leaves manual actions available.
- Commands require both the target session ID and revision; Room validates and applies the normal transition in one transaction. Duplicate or queued callbacks cannot borrow in a replacement commute.
- Borrowing, cancellation, Bluetooth off, task removal and session replacement stop observation. Reentering the app can resume a pending commute after process termination.

## Automated verification

All **105 Android unit tests passed**, including six new cases for immediate ordinary-bike borrowing, prefix matching, selected backups, eligible phases, duplicates and replacement-session rejection. Debug and unsigned Release builds passed. App and platform lint reported **0 errors**; warning counts are recorded in the JSON result.

Both APK manifests declare the unexported `connectedDevice` service and required Bluetooth/foreground permissions, retain minimum SDK 29, and omit `BLUETOOTH_SCAN`. The diagnostic observation service is present only in Debug. APK hashes and per-module test counts: [borrow-detection-verification.json](borrow-detection-verification.json).

## Android 13 / API 33 smoke test

A current-format test route was seeded in the emulator; route creation and source inventory were outside this test's scope. The latest build passed:

- Deny Nearby devices, then manually borrow, return and finish without a foreground observer.
- Allow Bluetooth access and start observation from an active commute.
- Keep observation running on the home screen; display the selected station in the foreground notification and update it after selecting a backup.
- Stop the observer after manual borrowing or cancellation.
- Force-stop the app, reopen it, restore the pending commute and restart observation.
- Turn Bluetooth off and stop observation; rapidly turn it on and reenter the app three times, successfully recovering each time.
- Wait beyond the foreground-service timeout window and verify **0 app crashes** in the final-build crash buffer.

The first smoke run exposed a foreground-service startup timeout during rapid Bluetooth off/on and app reentry. Startup now checks Bluetooth readiness before requesting the service and promotes every accepted start request, including reused Service instances, before adapter-state exits. The activity retries when Bluetooth becomes ready. The final regression run above used the rebuilt APK.

Notification evidence: [waiting for bike](10-youcommuter-borrow-detection-notification.png). Test-app data was cleared and the emulator stopped after verification.

This emulator run verifies permissions, persistence and service lifecycle. The connection-to-borrow transition was tested through the production domain rule and reducer, rather than a physical YouBike connection or an injected Android Bluetooth event. A physical rental with this new build remains untested; the `EYB` naming rule and connection-as-success behavior are the owner's chosen assumptions.
