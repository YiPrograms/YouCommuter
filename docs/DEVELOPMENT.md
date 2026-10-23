# Developing YouCommuter

## Current runtime

Android inventory is independent of the prediction backend. `core:data/YouBikeInventory` reads the anonymous official area/station feed, normalizes counts and source timestamps, and serves region filtering/search/paging locally. A shared mutex/cache coalesces concurrent screen/reminder reads. Source timestamps, measured zero and unknown subtypes remain distinct. Mobile 429 responses honor Retry-After before the next source request. The accepted cache is in memory, not an offline database.

The optional prediction repository reads the latest user configuration for each request. The complete URL and optional bearer key are configured in a full-page screen reached from the commute toolbar. Blank disables predictions. API keys use Android Keystore AES-GCM. Client instances do not share authentication with YouBike; redirects and oversize prediction responses are rejected. Saving a provider change advances a revision, clears posted departure notifications while future work reads the new configuration. Old in-flight results cannot be displayed or posted after the change.

Local Room repositories own routes and manual commute state. WorkManager schedules seven days of reminders with daily reconciliation. Notifications preserve current observations from YouBike; optional forecasts use departure time and an optional user-supplied return duration. Unknown predictions never become numerical probabilities. Unconfigured prediction hides the forecast rows rather than showing setup instructions in every notification. Current-only notifications also expire at the source inventory’s staleness deadline, even without a forecast; already stale/unavailable readings retain explicit warnings.

Normal inventory does not display a “Live” label. Only unavailable, aging/stale or closed states are shown. General and electric bikes share a compact bike/bolt group, separate from docks. Saving and consequential borrow/return actions retain short visible labels. Station/route authoring uses full-page flows and localized accessibility names.

Before borrowing, the commute loads both selected stations independently: bike subtypes from the borrow station and docks from the return station. Both backup selectors are available then; after borrowing only the return section remains. An unchanged station retains its data when the other selection or commute phase changes. Refresh reloads the visible stations, and cancelled requests cannot replace a new selection. Terminal sessions clear inventory requests.

The Go backend exposes `/v1/forecasts:batch` and health checks only. The anonymous collector and history database are private prediction dependencies. Installation credentials, public station/coverage/search endpoints and their adapters have been removed. Capacity/system/relocation epochs still isolate history; a portable `stationVersion` fingerprint, not the database epoch ID, is exposed to clients.

## Layout

```text
android/
  app/                 Composition, prediction settings and encrypted preferences
  core/model/          Pure values and prediction configuration
  core/domain/         Repository ports, reducer, freshness and reminder rules
  core/data/           Direct YouBike adapter and prediction protocol
  core/database/       Room routes and session
  core/platform/       Notifications, WorkManager, capability adapters
  core/ui/             Shared accessible icons and grouped inventory
  core/map/            Google Maps Compose, clustered markers and current counts
  feature/             Commute, station browsing and route editor
backend/
  cmd/{server,collect,initdb,healthcheck}/
  internal/provider/youbike/  Anonymous source adapter
  internal/ingest/            Durable collector and private health
  internal/postgres/          Inventory/history storage
  internal/forecast/          Bounded experimental estimator
  internal/httpapi/           Prediction-only HTTP transport
  internal/station/           Values, freshness and portable metadata version
```

## Backend

```sh
docker compose up --build -d --wait
curl --fail http://127.0.0.1:18080/health/ready
make check-fmt test-backend integration integration-compose
```

Change `YOUCOMMUTER_HTTP_PORT` in `.env` if needed. Use `PREDICTION_API_KEY` only when the prediction deployment requires a key. No YouBike credentials exist. The collector starts by default, checks one nationwide feed per minute, commits snapshots/validators atomically, honors 304 and durable Retry-After, and holds a provider-wide singleton lock.

The single current schema is embedded in `db/schema.sql` and applied by `initdb`; no migration engine or historical transformations are maintained. Repeated initialization checks the exact schema checksum. Run `docker compose down --volumes` before replacing an older development deployment. This removes its history; no data conversion or compatibility layers are provided. Future runtime/storage changes must keep contract fixtures and current source code consistent.

## Android

Use JDK 21 and the installed Android SDK. Dependencies are pinned and locked.

```sh
cd android
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :core:domain:test :core:data:test :core:database:testDebugUnitTest \
  :feature:routes:testDebugUnitTest :feature:stations:testDebugUnitTest :feature:commute:testDebugUnitTest \
  :core:map:testDebugUnitTest \
  :app:testDebugUnitTest :core:platform:lintDebug :app:lintDebug
```

There is no build-time server URL. Fresh installs fetch YouBike inventory directly and start with predictions disabled. For a local service on port 18080, enter `http://10.0.2.2:18080/v1/forecasts:batch` in the emulator’s prediction settings. Release accepts HTTPS only. Release APKs are unsigned until deployment signing is configured.

Google Maps uses a build-time client key, independent of the runtime prediction service. Set `youcommuter.mapsApiKey` in your Gradle user properties, `YOUCOMMUTER_MAPS_API_KEY` in the environment, or `MAPS_API_KEY` in ignored `android/local.properties` (in that precedence order). A blank key keeps list/inventory flows usable and shows the map's unavailable state. `android/local.properties.example` is a template. Do not copy keys from unrelated public apps or demo projects.

Enable billing and Maps SDK for Android on your Google Cloud project. Restrict the key to that SDK and Android app package/signing SHA-1 pairs. The minimum supported OS is Android 10 (API 29). The debug package is `dev.yikuo.youcommuter.debug`; obtain its certificate with `./gradlew :app:signingReport`. Release uses `dev.yikuo.youcommuter` and the actual release/Play signing certificate. The code uses no Map ID, cloud styling, Street View or Places. Google's standard Maps SDK SKU is currently unlimited/no charge; requirements and billing triggers are linked in `ARCHITECTURE.md`. Authenticate and render the real basemap with your restricted key before release.

The connection test uses a fixed public station and a synthetic 15-minute arrival, not personal route information. A valid `building` or `unavailable` result proves protocol connectivity; it does not establish forecast accuracy or service coverage for every station.

## Shared contracts and tests

Go and Kotlin share freshness, forecast and portable station-version fixtures. Raw YouBike fixtures verify regular/electric/dock normalization and missing timestamps. Direct-adapter tests use a local HTTP fixture to cover conditional requests, source age, errors and cache preservation. Prediction tests cover disabled operation, exact endpoint/key use, response matching and rejecting results from a previous provider. PostgreSQL/Compose tests exercise transactions, epoch isolation, retention, retries, singleton locks and restart recovery.

Test code does not use a production database URL. A real-source emulator run separately verifies standalone inventory and service settings. Record limitations in `e2e-artifacts/REPORT.md`.

## Remaining work

Cloudflare Workers deployment, compact historical storage, forecast calibration/backtesting, GPS nearby search, road routing, learned ETA, widgets and automatic rental detection remain separate work. Native maps are implemented for station browsing/selection and active commutes. The debug-only [Bluetooth observation tool](BLUETOOTH_FIELD_TEST.md) supports physical field research without unlocking bikes or changing state automatically. Manual commute completion does not verify an official rental transaction.
