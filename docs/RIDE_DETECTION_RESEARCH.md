# Borrow/return detection research

Initial research and source audit: **2026-10-19**; product rule updated **2026-10-21**. Product priority: the owner mainly borrows through the **YouBike app**. Optimize detection for that path; retain manual controls for card borrowing and unsupported devices.

## Decision

The owner confirmed that the latest named peripheral was an **ordinary bike**, asked to assume all target names start with **`EYB`**, and chose to treat connection as successful borrowing. The implemented rule immediately applies ConfirmBorrowed for a new matching ACL connection while the current commute is awaiting borrowing. It uses the selected borrow station and requires no UUID, numeric suffix, link duration, disconnection, location or cycling gate. The prefix never determines bike type. A Bluetooth disconnect must never mean “returned”: the inspected app explicitly disconnects during its unlock-success flow. Return confirmation remains manual.

This is a product heuristic rather than a verified rental-result interface. The foreground Bluetooth observer is implemented for debug and release, starts from the resumed app for a user-started commute, and stops on borrowing/cancellation/session replacement/Bluetooth off. Missing permission keeps manual controls usable. Process death or task removal does not restart it; foreground entry can resume a pending commute. The debug-only field tool remains a separate diagnostic capture. The original static audit had no physical capture; the user subsequently supplied three field traces analyzed below.

## What the repository actually implements

| Area | Current implementation | Remaining work |
|---|---|---|
| Manual ride | `CommuteReducer` supports confirmed borrow/return, cancellation, and saved station switching | Keep these commands available throughout detection |
| Recovery | Room persists the route snapshot and one current/latest session | Sensor continuity, reboot handling, retained ride history, inferred-event correction |
| Permissions | Bluetooth connection permission is requested for an active commute; map location and notification grants have their own flows | Activity/location inference adapters |
| Detection | New `EYB` connection confirms borrowing; commands validate target session ID and revision | Automatic return, location/activity inference, measured field accuracy |
| Notifications | Custom departure reminders plus an awaiting-borrow foreground notification with selected station | Active ride counts/actions, inferred-completion undo |
| Time estimates | Optional user-supplied return duration; experimental station-count forecasts | Confirmed ride records and learned personal arrival ETA |
| Other unfinished slices | Region/name station browsing and durable nationwide YouBike snapshot collection exist | Nearby/map UI, routing, forecast calibration, widgets, holiday handling, server push |

Relevant implementation files:

- `android/core/domain/src/main/kotlin/dev/yikuo/youcommuter/core/domain/CommuteReducer.kt`
- `android/core/model/src/main/kotlin/dev/yikuo/youcommuter/core/model/Commute.kt`
- `android/core/database/src/main/kotlin/dev/yikuo/youcommuter/core/database/LocalRepositories.kt`
- `android/core/database/src/main/kotlin/dev/yikuo/youcommuter/core/database/Daos.kt`
- `android/core/platform/src/main/kotlin/dev/yikuo/youcommuter/core/platform/LocationCapability.kt`
- `android/app/src/main/AndroidManifest.xml` and `android/core/platform/src/main/AndroidManifest.xml`

The repository requires the target session ID as well as the command revision and validates both in the transaction; a queued connection cannot affect a later session with the same revision. It stores only `lastCommandId`, not a durable history of processed sensor events. Do not claim complete replay deduplication from that last-command check. Leaving the awaiting-borrow phase stops connection observation and prevents repeated borrowing.

## APK provenance and limits

The [official Google Play listing](https://play.google.com/store/apps/details?id=tw.com.youbike.plus) identifies package `tw.com.youbike.plus`. The old `tw.com.sstc.youbike` app is a different product and was not used for this analysis.

Downloaded the arm64 split bundle from the [APKPure download page](https://apkpure.net/youbike%E5%BE%AE%E7%AC%91%E5%96%AE%E8%BB%8A-%E5%AE%98%E6%96%B9%E7%89%88/tw.com.youbike.plus/download), using its version-specific link. This is a third-party mirror, not a download directly from Google Play. The page labels the upload 2026-10-17; this is a mirror upload date, not a verified official release date.

| Property | Inspected artifact |
|---|---|
| Package / version | `tw.com.youbike.plus` / **3.14.0 (1413)** |
| Platform | arm64-v8a; min SDK 28, target SDK 36 |
| Framework | Flutter; Dart 3.11.1 AOT |
| Base APK SHA-256 | `d6e49d563708b360d6046d489e43b5a4d7a60f6c0aafb019cf6558a336557dda` |
| XAPK SHA-256 | `faed4b66277f549e5262208cc06fd92ece2bd4d465c0b80dd3e1ad69fa0abc42` |
| Signing certificate SHA-256 | `8651f28e40bffa268cdbf45d869d5051d4cd3d7014218504e8f7f280e8030096` |
| Signing certificate SHA-1 | `438f528e639c5294a630dc7b5909fe8e4d3c609e` |

`apksigner verify --print-certs` passed for the base APK. Its SHA-1 certificate fingerprint matches the mirror's published fingerprint. A first-party installed copy was unavailable for independent publisher-certificate comparison; signature validity alone does not establish publisher identity. Findings below are specific to this artifact and must be checked against the version used during field tests.

Analysis used Android SDK `apkanalyzer`, JADX 1.5.6, and [Blutter](https://github.com/worawit/blutter) commit `4a60ac648bf448c5a7596437243bcd0b9376fdf0`. JADX reported 46 decompilation errors across the app; it was not treated as a complete source recovery. Blutter successfully generated annotated AOT assembly for the relevant Dart methods. No APK installation, account login, rental/payment request, or bike connection was performed. No credentials or unlock payloads are included here.

Raw downloads and analysis outputs are outside the project at `/tmp/youcommuter-youbike-research-20261019/`. They are temporary working files, not project dependencies or a permanent archive. The hashes, method names, and code offsets below identify the evidence without redistributing the app's code.

## Findings from the inspected app

| Finding | Static evidence | Implication |
|---|---|---|
| The scan flow really uses BLE GATT | Manifest declares `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; `FlutterBluePlugin` calls `BluetoothLeScanner.startScan`, `connectGatt`, service discovery, and characteristic notifications/writes | This is stronger evidence than a permission declaration alone |
| QR parsing precedes Bluetooth work | `scan/scan_page.dart`, `_ScanPageState::_startConnect` at `0x7a5334`, calls QR analysis, permission checks, then `ScanViewModel::unlock` | Nearby BLE traffic by itself is not a borrow event |
| The app selects a scan candidate, connects, then exchanges application data | `scan/bluetooth.dart`: `BluetoothManager::startScan` at `0x7a5884`, `_startConnect` at `0x7a5bc4`; `scan/scan_view_model.dart`: unlock chain at `0x7a5758` and its continuations | A connection can precede success, failure, retry, or temporary-lock operations; the OS connection event does not reveal the outcome |
| **The success path disconnects immediately** | `scan/scan_page.dart`, `_showSuccessDialog` at `0x7ae100`, calls `ScanViewModel::disconnect` at `0x7ae160`, before its success event/dialog calls | A disconnect can happen at the beginning of a ride; never use it as a return trigger |
| Leaving the scan flow also cleans up the connection | `ScanViewModel::dispose` at `0x850b38` calls `BluetoothManager::disconnect` | Disconnection can also be UI cleanup |
| Homepage rental status has an account-data path | `map/map_view_model.dart`, `refreshTw2Card` at `0x8754ec`, checks login and calls `CardRepository::getCards`; rental getters inspect those cards | The displayed rental state is not evidence of a continuously connected bike. These are internal account-data calls, not a verified third-party integration API |
| No public rental-event interface was found | Examined manifest exports, app native bridges, and BLE callbacks; BLE results use Flutter channels inside the app | Do not assume YouCommuter can subscribe to the official app's unlock result. This is a bounded negative finding, not proof that no integration could ever exist |

Official materials are consistent with this distinction. The [YouBike listing](https://play.google.com/store/apps/details?id=tw.com.youbike.plus) directs riders to the bike screen and transaction history to confirm a return. The [2026-09-23 service announcement](https://youbike.com.tw/region/taitung/news/service/6ab22c3f800ac9b2d20f01c3/) describes rental-state information on the app homepage, including card rentals. That announcement does **not** document an Android system notification for every borrow/return.

Static analysis establishes call paths, not observed over-the-air behavior, delivery latency, or compatibility with every controller/firmware. In particular, advertising identity, visibility while connected, and correlation with successful/failed attempts still require field measurements.

## What YouCommuter can observe

| Signal | Access and useful role | Limitation |
|---|---|---|
| System GATT connection snapshot | `BluetoothManager.getConnectedDevices(GATT)`; optional initial snapshot during an explicitly started commute | Android documents this as profile-wide, not per app. It does not identify which app owns a client or expose the official app's GATT payloads. An empty list can mean an error |
| ACL connect/disconnect broadcasts | With Bluetooth permission, observe low-level links while the detector is running | They identify a link, not a rental, its account, station, or success. LE delivery, timing, and device identification need physical-device checks |
| BLE advertisements | Potentially correlate a connection with a verified bike/controller fingerprint | Passive observation means listening only; this still requires scanning permission and measured identifiers. A generic UART UUID, device name, RSSI, or MAC prefix is not sufficient attribution. Advertising may stop or change |
| Activity transitions | Detect cycling/walking/vehicle transitions for departure and return evidence | Motion classification is delayed/device-dependent and does not identify a rental |
| Accurate location and station geometry | Attribute the encounter and distinguish leaving by bike from leaving on foot | Approximate, old, missing, or overlapping fixes must keep station identity uncertain |
| Official app notifications | Possible future optional hint, only if real samples prove useful | Requires separate user-enabled notification access. No reliable per-rental payload was established here; do not build a parser from guessed text |
| Official account state | Could be authoritative with a supported, authorized integration | No public third-party rental-status contract was found in this research. Existing product scope does not connect a YouBike account |

Android's [BluetoothManager reference](https://developer.android.com/reference/android/bluetooth/BluetoothManager#getConnectedDevices(int)) explicitly describes the profile-wide snapshot. Its [BluetoothDevice reference](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#ACTION_ACL_CONNECTED) defines ACL link events. These APIs justify investigating passive connection observation; they do not justify claiming access to another app's transactions.

For an observation-only connection prototype, request `BLUETOOTH_CONNECT` on Android 12+ and the legacy Bluetooth permission on supported older versions. Add `BLUETOOTH_SCAN` only if the field investigation demonstrates that advertisements are needed. The [permission guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) requires location permission when scan results derive physical location; do not copy `neverForLocation` from the inspected app into a station-attribution scanner without satisfying that assertion. It can also filter beacons.

The diagnostic capture uses an initial GATT snapshot plus system callbacks, not continuous profile polling. The production borrow observer uses only new connection callbacks; it never interprets an already-connected snapshot as a fresh borrow. While observing a user-started commute, use a lifecycle-scoped receiver with the appropriate exported-receiver handling for Bluetooth system broadcasts. Ignore unrelated device names and do not open a GATT connection just to identify a bike. The owner's `EYB` assumption drives the current product rule; a validated fingerprint and measured error rate remain future evidence.

Android's [BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background) requires an advertising peripheral for scan-based wakeups and discourages periodic scanning. [Foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) still apply: receiving a Bluetooth event is not general permission to start location tracking from the background.

## Next implementation, kept small

### 1. Validate the app-borrow signal on a real phone — observation tool implemented

The debug build now provides **Borrow test / 借車測試** from the commute toolbar, including when there are no routes or backend connection. It runs an explicitly started, nonsticky `connectedDevice` foreground service, takes an initial system GATT snapshot, and observes ACL connect/disconnect events. It stops on user request, Bluetooth off, permission loss, or a 15-minute limit. A capture launched from a commute also stops on that session's replacement/completion; standalone captures do not bind to commute state. Manual markers and a user-requested JSON export support field comparison. It does not scan advertisements, request location, or open a GATT connection. See [field-test instructions](BLUETOOTH_FIELD_TEST.md).

Observation IDs scope callbacks and notification stop actions. The process-local log caps events at 200 and device identities at 64; a fresh capture replaces the stopped capture. Raw addresses are only in-memory mapping keys and are cleared on stop. Export contains per-capture numbered device aliases and relative monotonic times. Schema v2 also includes cached names, UUIDs, type and class; it does not export address fields, account data, QR values, coordinates or commute IDs. Process death discards the capture; nothing is uploaded or automatically resumed. This diagnostic entry point and service are excluded from release; Bluetooth permissions are shared with the production borrow observer. Start before scanning in the official app so its short connection can be observed.

First answer these questions: can a device be recognized as the selected YouBike without opening another connection; does an unsuccessful attempt look identical; does the connection disappear before departure; and does return produce any additional signal? If no distinguishing signal exists, retain BLE as an optional attempt hint and proceed with location/activity detection.

### 2. Future location/activity assistance during a user-started commute

This section describes an optional future path. Its evidence gates do not apply to the owner's implemented direct `EYB` connection rule.

Start with the existing **出發** flow. Request foreground location and activity recognition when the user enables assistance; Bluetooth observation remains optional. Begin a compliant location foreground service while YouCommuter is visible, before switching to the official app. No unattended session creation or background-location permission is needed for this first slice.

Keep Android adapters in `core:platform`, event interpretation in pure `core:domain` rules, and transactional persistence in `core:database`. Do not put detection rules into composables, broadcast receivers, or BLE callbacks. The existing `RouteStation` contains only ID/name: retain the relevant station geometry at session setup, or disable attribution when it cannot be obtained; never fabricate a position while offline.

The first release of assistance proposes a borrow/return confirmation through the existing command path. It does not automatically mark a ride completed before correction/undo and field validation exist. This is a staged rollout of M4; strongly inferred automatic completion remains the later product target.

Borrow evaluation:

1. Require the active session and a uniquely attributable borrowing-station encounter.
2. Optionally attach a correlated bike connection as an attempt hint. Its absence does not imply failure; its disconnect does not imply return.
3. Require sustained cycling and departure evidence from the same encounter, using the gates and durations in `ALGORITHMS.md`.
4. Present one short confirmation when evidence is useful. Keep uncertain or overlapping station identity selectable; do not silently choose the planned station.

Return evaluation uses the compatible-station encounter, cycling exit, walking departure, and no-cycling interval from `ALGORITHMS.md`. Bluetooth disconnection contributes **no completion evidence**. A stop, a traffic light, walking a borrowed bike, and temporary locking must not become an asserted official return.

Keep the UI to status, station/count icons, and short actions such as **已借車** / **已還車**. Diagnostic detail belongs in the debug flow. Manual actions remain available if any permission or service is unavailable.

### 3. Future automatic location/return transitions after correction and replay safety

Before automatic borrow/completion, persist event origin, observed/received time, boot identity and monotonic time, time uncertainty, inferred/confirmed provenance, and the target session ID. Persist each applied event and resulting revision atomically, with bounded deduplication. Reject delayed or contradictory evidence after manual correction; preserve the specified three-minute suppression window.

Add inferred-endpoint confirmation, **仍在騎乘** undo, and recent-record retention before clearing/replacing a completed session. Resuming location tracking must follow a valid user action/foreground entry. Only confirmed eligible endpoints may train personal ETA. Stop tracking on inferred completion as specified, without displaying a claim that YouBike has closed the rental.

Do not change the existing heuristic points to invent a BLE confidence score. Add a versioned BLE contribution only after labeled field data measures its benefit. Automated precision/delay targets in the original milestone remain goals, not achieved results.

## Validation cases and current evidence

| Case | Required outcome / measurement |
|---|---|
| New `EYB` connection while awaiting borrowing | Immediately enter Riding at the selected borrow station; preserve bike-type settings |
| Failed QR attempt, retry, or cancel | Under the chosen rule, a matching connection still confirms borrowing; measure these false positives for later refinement |
| Unlock success followed by no departure | Remain uncertain; do not treat disconnect as return |
| Temporary lock / unlock | Do not finish or create a second ride |
| Walking the rented bike, pausing near a station, red light | No assertion of an official return |
| Successful return with app closed, offline, or Bluetooth off | Location/activity path and manual action remain usable |
| Multiple nearby bikes/stations; unrelated headphones/watch | Ignore names without the `EYB` prefix; use the selected station, never nearest RSSI |
| Permission revoked, approximate fix, unavailable Play Services | Fall back to manual; stop unsupported tracking |
| Process death, reboot, delayed/duplicate event, manual correction | No stale transition against another session; replay is deterministic |
| Real 2.0 and 2.0E controllers on supported Android/OEM versions | Record actual event availability, false prompts, missed detections, detection delay, and battery use |

Completed: repository audit, official-source review, artifact hashes/signature check, manifest/native inspection, annotated Dart AOT call-path inspection, debug connection observation, the owner's direct connection-to-borrow rule, and analysis of three user-provided link traces including an ordinary bike with a cached device name. Automated/local verification is recorded in `e2e-artifacts/REPORT.md`. Not completed: validated device fingerprint, over-the-air payload capture, live rental API verification, automatic return/location/activity inference, or physical-device precision/recall measurement. Emulator tests cannot establish YouBike radio behavior or real-world rental accuracy.

## Deeper Bluetooth mechanism findings — implementation follow-up

Further inspection of the same artifact follows the data from `ScanViewModel::analysisQRcode` (`0x7ac0f8`) to `BluetoothManager::startScan` (`0x7a5884`) and `_filterDevice` (`0x7ab1d0`):

1. The app validates numeric code input, pads it to ten characters, and decodes it into five bytes. This is a code-derived scan selector, not proof of a stable bike identifier.
2. The scan predicate reads manufacturer-specific advertising data under key `0xffff`. The native `ProtoMaker` bridge preserves Android's manufacturer IDs and their byte arrays, confirming which advertisement field the Dart code receives.
3. The filter checks an initial seven-byte sequence consisting of the two-byte prefix `02 15` followed by the five code-derived bytes. It does not rely only on the advertised device name. These are functional format observations, not an independently authenticated YouBike identity. The manufacturer ID and prefix alone must not classify arbitrary nearby devices as YouBike.
4. After selection, the app connects, discovers a custom GATT service in the `FE400001…` family, and enables a notification characteristic. The unlock chain checks application-level status, exchanges code/card-related data, and evaluates the resulting unlock response. An OS ACL event contains none of those responses. This research does not reproduce their command payloads or execute them.
5. The already identified success path explicitly disconnects. Thus both a failed/cancelled attempt and a successful rental can produce a short connection window; link duration or disconnection is not sufficient outcome evidence.

These findings support observing system connections first, without racing the official app by opening a second GATT client. They do not establish that every controller advertises continuously, that a code is globally unique or stable, that a MAC never rotates, or that return has any BLE event. Those questions remain physical measurements.

Keep the app's logical GATT-client cleanup distinct from the system's low-level ACL link. The profile-wide APIs do not identify the owning client, so an observed disconnection timestamp must not be assumed to equal the official app's cleanup call; stack behavior or other clients may affect it. This is another reason to collect labeled field traces instead of converting connection duration into a rental outcome.

## User-provided connection traces and cache snapshots — 2026-10-20

The owner supplied `youcommuter-borrow-observation.json` and `youcommuter-borrow-observation2.json`, and confirmed that they concern different bikes. Both schema-v1 traces contain a single LE connect/disconnect pair around the manual successful-borrow marker: 10.524 seconds and 10.509 seconds respectively. In the second trace, a manual failed-borrow marker precedes the connection, and the manual return marker occurs 696.216 seconds after disconnection with no additional recorded link event. The first capture times out without a return marker. Marker times describe user button presses, not independently verified lock transitions. Neither trace identifies the peripheral; the per-capture device number cannot link devices across files. Two similar durations are evidence to investigate, not a YouBike fingerprint or a rental outcome rule.

The debug tool now reads the `BluetoothDevice` already attached to each ACL event: cached name, cached service UUIDs, device type and class. The same snapshot is taken for initially connected GATT devices and on disconnection. This uses the existing connection permission, without scans, service discovery or an additional GATT client. Missing/failed optional cache fields stay unknown; permission revocation still stops observation. The event timestamp is captured before cache reads, so reading metadata does not shift the recorded receipt time. No fixed callback-latency guarantee is established by this implementation.

Schema-v2 exports retain each event's own cache snapshot and anonymous device number; names are included, raw addresses remain memory-only. Cached UUIDs may be unavailable or incomplete. The field tool now marks `EYB` names with a bike icon using the same product rule as the production observer. Continue comparing cache fields across known 2.0/2.0E bikes and unrelated peripherals; only add advertising capture if needed. See [Android cached name](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#getName()) and [cached UUIDs](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#getUuids()), plus the [field guide](BLUETOOTH_FIELD_TEST.md).

The [AOSP `RemoteDevices` implementation](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/RemoteDevices.java) also emits public ACL events with transport information while the adapter is fully on and protects delivery with `BLUETOOTH_CONNECT`. Its BLE-only adapter states use different internal events. The observers intentionally check that Bluetooth is on; platform/OEM/version behavior still needs field validation. The chosen connection-to-borrow rule does not expose the official transaction payload.

## Named connection field trace — 2026-10-21

The owner supplied a schema-v2 `youcommuter-borrow-observation.json` starting at `2026-10-21T00:48:07.630149Z`. It contains one LE connection and disconnection, one manual successful-borrow marker, and a manual stop. The cached name is already present in the connection event: `EYB` followed by eight digits. The full identifier stays in the supplied capture; it is not needed as a source-code constant or recognition rule.

| Event | Time since capture start |
|---|---|
| LE connected; cached name available | 7.033 s |
| Same capture-local device disconnected | 17.106 s |
| Manual successful-borrow marker | 17.132 s |
| Observation stopped | 352.130 s |

The observed link lasts **10.073 seconds**. The success marker is **26 milliseconds after disconnection**, but is still a user action, not a lock-response timestamp. Both link events contain the same metadata: LE device type, device class `7936`, and `cachedServiceUuids: null`. The UUID field means unavailable cache data, not proof that the peripheral exposes no services. No return marker or additional link is recorded.

This confirms that the existing observation path can receive a name at connection time on the tested phone, without scanning or opening another GATT connection. The owner confirmed this sample was an **ordinary bike**. It does not establish callback latency relative to the physical unlock. The name and temporal correlation do not independently authenticate YouBike or prove successful rental; nevertheless, the owner explicitly chose the simple `EYB` connection-to-success product rule. A universal naming rule has not been independently verified. Keep this sample distinct from the two earlier captures: their anonymous device number does not identify the same bike across files.

Further field validation can compare ordinary and electric bikes, failed/cancelled attempts, temporary unlocks, and unrelated peripherals. Record the actual bike type and, if readily visible, whether its printed number matches the cached-name suffix. These measurements can refine the rule later; they are not prerequisites for the currently requested behavior.
