# YouCommuter

- Application ID: `dev.yikuo.youcommuter`; debug adds `.debug`.
- Keep Android 10 / API 29 as the minimum supported OS. Preserve the platform handling needed for supported Android versions.
- Internal storage and API formats have one current definition. Do not add old-field defaults, historical readers, renamed-field aliases, conversion migrations or old-name forwarding paths. Breaking changes may rebuild development data.
- Initialize PostgreSQL from `db/schema.sql`. A different schema checksum requires recreating the database.
- Treat YouBike as an external source: project the fields we use and retain validation, freshness checks and network failure handling.
- Prefer simple, maintainable code. Keep UI text minimal and functional, with icons where clear.
- Backend checks: `make test-backend integration integration-compose`; Android checks: `make test-android build-android`. Use JDK 21 and the configured Android SDK.
- Keep credentials in ignored local configuration. Never print Maps keys or unfiltered merged manifests/logcat.
