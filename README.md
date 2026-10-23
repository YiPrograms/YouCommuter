# YouCommuter

Android YouBike commute companion. The app reads station inventory directly from YouBike; an optional, replaceable service predicts future bike and dock counts.

- Group regular/electric bikes together, with return docks separately.
- Browse/search stations without an account or prediction server.
- Save local routes, backups, thresholds and departure reminders; manually confirm borrowing/returning.
- Configure a complete prediction endpoint and optional API key in the commute toolbar’s settings page. Leave the endpoint blank to disable predictions.
- Normal inventory has no “Live” badge; stale, missing and closed states remain visible.

## Run a prediction service

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD before public deployment.
docker compose up --build -d --wait
```

The stack runs PostgreSQL/PostGIS, a private YouBike collector, a schema initializer and the prediction API. No upstream account/API key is needed. Public API: `POST /v1/forecasts:batch`, plus health checks. Set `PREDICTION_API_KEY` to require a bearer key; leave it empty for a public service.

API default: `http://127.0.0.1:18080`. Use `YOUCOMMUTER_HTTP_PORT` to change it. Production mobile endpoints require HTTPS; the debug build also permits the emulator host `10.0.2.2`.

The current schema is a fresh-install baseline, initialized by `/initdb`. Repeated initialization checks its checksum; schema changes require `docker compose down --volumes` and a rebuild. See [deployment](docs/DEPLOYMENT.md).

## Android

```sh
cd android
./gradlew :app:assembleDebug
```

No server URL is compiled into the APK. Enter the **complete** endpoint in the app, for example `https://your-host/v1/forecasts:batch`. For local emulator testing with port 18080 use `http://10.0.2.2:18080/v1/forecasts:batch`. No public prediction host is bundled or assumed to exist.

## Validation

```sh
make check-fmt test-backend integration integration-compose
make test-android
```

Source fixtures and a portable station-version fixture are shared across Go and Kotlin. Database and Compose tests use isolated fake YouBike services. Emulator evidence is recorded in [the E2E report](e2e-artifacts/REPORT.md).

## Boundaries

Predictions currently use an experimental analog-delta model. A new service must collect enough history; shortage probabilities remain unavailable until calibration/backtesting exists. Google Maps covers station browsing/selection and active commutes, verified with real basemaps on the API 33 emulator. Configure a restricted Android Maps key for your build as described in [DEVELOPMENT.md](docs/DEVELOPMENT.md).

During a user-started commute, a new Bluetooth connection whose cached name starts with `EYB` immediately confirms borrowing at the selected borrow station. Allow Nearby devices on Android 12+; manual borrowing remains available. The observer stays active while switching to YouBike and stops on borrowing or cancellation. It does not scan, connect, infer bike type, or treat disconnection as a return. Reopen YouCommuter to resume observation after process death, task removal or Bluetooth being turned off. Returns remain manual. GPS nearby search, road routing, activity/location rental detection, learned ride ETA and widgets remain future work. The debug-only Bluetooth field tool records raw events and manual test markers separately.

[Architecture](docs/ARCHITECTURE.md) · [Prediction protocol](docs/PREDICTION_PROTOCOL.md) · [Development](docs/DEVELOPMENT.md) · [YouBike research](docs/YOUBIKE_API_RESEARCH.md) · [Algorithms](docs/ALGORITHMS.md)
