# YouCommuter verification — 2026-10-21

Latest Bluetooth borrowing implementation and verification: [BORROW_DETECTION_REPORT.md](BORROW_DETECTION_REPORT.md). The identity/deployment run below predates that change; its APK hashes describe that earlier build.

The workspace is `/home/yi/Workspace/YouCommuter`. Release uses `dev.yikuo.youcommuter`; debug uses `dev.yikuo.youcommuter.debug`. The APK label is YouCommuter and minimum SDK remains 29 (Android 10). The launcher now uses a Y, with a monochrome variant on Android 13+.

Project, Go module, Kotlin packages, Room export paths, work tags, resource names, environment variables, database/image/Compose names and documentation use the current identity. The previous workspace, development stack/volume, unused image tags and generated artifacts were removed. Source, both APK contents and backend binaries passed the identity scan. Supported-Android handling remains in place.

## Simplifications

- Stored routes require all current preference fields. Sessions require `routeSnapshot`, `selectedBorrow` and `selectedReturn`; no reader substitutes missing fields.
- Departure work stores only route ID, revision and departure time. Workers load the current route and skip deleted or changed routes. Optional provider defaults, parallel name/ID arrays and incomplete single-endpoint rendering were removed.
- Prediction responses require declared fields and recognized enum values. Observation station identity is explicitly validated. YouBike's external feed remains a projection of the fields used by the application.
- PostgreSQL has one `db/schema.sql` and transactional `/initdb`. Initialization is repeatable only for the matching checksum; other schemas require rebuilding. The migration engine, history scripts and dependency were removed.

## Validation

- **99 Android tests passed**, including rejection of incomplete stored preferences/selections and malformed prediction responses.
- Debug and unsigned Release assemblies passed with strict dependency locks. Lint: **0 errors, 2 existing warnings** (`GradleDependency`, `UseKtx`).
- `go test -race ./...`, `go vet ./...`, backend builds, isolated database integration and Compose integration passed. Integration covers repeatable initialization, rejected schema mismatch, atomic snapshots/validators, 304, electric counts, stale/malformed data, durable 429 delay, restart recovery and collector singleton behavior.
- The new local stack is running at `http://127.0.0.1:18081`; API, collector and PostgreSQL were healthy. A check found 9,702 current station records in the freshly initialized database.
- **Android 13 / API 33:** new-package install/start, prediction-service connection with the strict wire format, station selection, route persistence, commute session restoration and real basemap rendering passed. A scheduled reminder posted under YouCommuter with both station names/counts. Inspection of five reminder jobs found only the three current input keys. No app crash-buffer entries were observed.
- **Android 11 / API 30:** the same APK installed and started with the older-system palette, and connected to the current prediction service. No app crash-buffer entries were observed. This is an API 30 smoke test; API 29 support is declared by the APK and was not exercised on an API 29 emulator.

Evidence: [start](01-youcommuter-start.png), [prediction connection](02-youcommuter-prediction-connected.png), [route editor](03-youcommuter-route.png), [live map](06-youcommuter-live-map.png), [notification permission](05-youcommuter-notification-permission.png), [posted notification](07-youcommuter-notification.png), [Android 11](08-youcommuter-android11.png), [Android 11 connection](09-youcommuter-android11-connected.png).

The modern-emulator debug test data and app-locale override were cleared. Both emulator processes were stopped, and the task-created API 30 AVD was deleted. The new Compose deployment remains running. Test details, cleanup and APK hashes: [verification.json](verification.json). No physical rental/Bluetooth detection or prediction-accuracy claim is made by this run.
