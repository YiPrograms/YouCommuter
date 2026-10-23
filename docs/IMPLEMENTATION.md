# Implementation status

YouCommuter is an Android commuting assistant for YouBike. The application ID is `dev.yikuo.youcommuter` (`.debug` for debug builds), and Android 10/API 29 is the minimum supported OS.

Implemented:

- Direct anonymous YouBike station/location/inventory queries, normal and electric bike counts, explicit refresh and foreground three-second updates.
- Full-page route editing, ordered backups, route duplication and group swapping, icon bike selection and configurable quantity thresholds.
- Native Google Maps for browsing, selecting and commuting; foreground location, retained camera/list position and phase-specific commute overviews.
- Current primary/backup counts, priority emphasis, manual borrowing/returning session state and departure notifications.
- Short navigation transitions and Material You on Android 12+, with a palette for supported older systems.
- User-selected prediction endpoints and optional encrypted service credentials; no prediction backend is required for inventory or routes.
- Prediction API, private collector, PostgreSQL/PostGIS storage, Docker Compose deployment and one current schema initializer.
- Debug Bluetooth connection observation and privacy-conscious JSON export. Automatic rental detection is not implemented.

Persisted route/session fields and the prediction protocol are strict current formats. There are no old-name aliases, old-field defaults or data conversion paths. Schema changes require rebuilding local App/database data. See [the protocol](PREDICTION_PROTOCOL.md), [architecture](ARCHITECTURE.md) and [deployment](DEPLOYMENT.md).

Algorithm calibration, automatic rental detection and a full Cloudflare runtime remain future work. Emulator validation records are stored in [the E2E report](../e2e-artifacts/REPORT.md).
