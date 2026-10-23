# UI and UX specification

Current implementation decision (2026-10-20): station metadata/current inventory come directly from YouBike; the only optional backend capability is prediction through a user-configured full endpoint and optional API key. Prediction settings are a full page opened from the commute toolbar. Blank endpoint disables predictions. Normal inventory has no “Live” badge; stale, unavailable and closed warnings remain. Disabled prediction has no forecast setup prompts in notifications. Routes, schedules, thresholds, backup selection and notifications remain local; broader server-sync/push proposals below are superseded by this boundary.

## 1. Experience principles

1. Lead with the next action, not a directory of counts.
2. Before borrowing, show both borrowing and returning. After borrowing, remove borrowing information from the main dashboard.
3. Separate **now**, **arrival estimate**, **data freshness**, and **prediction quality** visually and semantically.
4. Expand alternatives when relevant; always allow manual inspection.
5. Preserve user control over station changes, notifications, and inferred ride events.
6. Never render an unknown count as zero, an old count as live, or an inferred return as an official rental confirmation.
7. Keep visible text minimal and functional: names, values, essential field/action labels, states, and actionable errors. Do not expose specifications, implementation details, design rationale, promotional subtitles, or unsolicited helper paragraphs.
8. Use the shared vector icons for familiar actions and bike/dock/time metrics. Icon-only controls need localized accessibility names, 48dp touch targets, and short long-press tooltips. Keep short visible labels on consequential actions such as confirming a borrow/return and saving.

Current inventory presentation: group regular bikes and electric bikes together using bike/bolt icons, with return docks as the separate group. Station browsing/selection shows all inventory. Before borrowing, the commute card shows only bikes at the selected borrow station and docks at the selected return station, each under its own station selector. Never show the borrow station's docks in that flow. Saved return backups can be selected before borrowing or while riding. After borrowing, retain only the return section. Each station loads and reports failure independently; switching one station preserves the other section, and cancelled requests cannot overwrite a new selection. Notifications likewise use borrow-station bikes and return-station docks. Do not give bike subtypes and docks three equal peer columns. Unknown subtype counts stay “—”; current thresholds and forecasts still use total bikes. Bike-type route preferences below remain planned.

## 2. Navigation and presentation system

### Top-level destinations

| Destination | Contents |
|---|---|
| 通勤 / Commute | Active session, upcoming departure, one primary action, recent completion |
| 站點 / Stations | Search, map/list toggle, favorite stations, station details |
| 路線 / Routes | Saved commute definitions, ordering, enable/disable, duplication, history |
| Settings, toolbar action | Permissions, notification channels, privacy, units, language, appearance, service status |

Ride history is reached from Routes and the completion card; it is not a fourth bottom tab. An active session remains available as a compact banner when navigating to another destination.

Use bottom navigation below 600 dp window width; at 600 dp and above, use a navigation rail. At 840 dp and above, station search/list and station detail can form two panes. Support resizing and rotation without resetting the session, draft, map selection, or scroll position.

### Implemented route editor and departure notification

The current Android slice uses full-page add/edit and full-page station selection. Keep these flows out of modal dialogs. Only discard/delete confirmations are modal. Use 16dp content gutters, a compact back toolbar, a persistent keyboard-aware save action, station selectors with adjacent backup actions, and wrapped day/reminder controls. Put less frequent warning-threshold adjustments behind a labeled summary. Back from the picker returns to the same draft; back from a changed draft asks whether to discard. Rotation preserves the draft. Process-death recovery for unfinished drafts remains pending.

Departure notifications use two content-sized rows, with a small bike/parking badge, station name, timed estimate/range or unavailable state, and a large explicitly current count. Insert fresh backup recommendations only when required. Compact notifications show route/time, short freshness status, and current→approximate availability for each timed forecast. All custom text is at least 11sp. Resource-based colors must remain legible if Android changes theme after posting, and app-localized labels must not fall back to the SystemUI language. Continue to respect Android's notification size constraints and retain the accessible notification summary.

The other screen/notification contexts below remain the broader product specification; the implemented slice is listed in `DEVELOPMENT.md`.

### Visual tokens

| Token | Default |
|---|---|
| Light background / surface | `#F6F8F7` / `#FFFFFF` |
| Light primary text / secondary text | `#172521` / `#51625C` |
| Light primary action | `#006B5B`, white label |
| Dark background / surface | `#101815` / `#19251F` |
| Dark primary text / secondary text | `#E4EEE8` / `#B7C8BF` |
| Dark primary action | `#77D8BB`, `#00382D` label |
| Healthy / caution / high risk | Semantic success, amber, red roles, each with icon and text |
| Unknown / stale | Neutral gray treatment; never a success color |
| Spacing | 4 dp base; 16 dp page margins; 12 dp card gaps; 20 dp card padding |
| Shape | 20 dp principal cards; 12 dp compact rows; fully rounded chips |
| Typography | Platform sans with CJK fallback; 16 sp body, 14 sp metadata, 24 sp heading, 40–48 sp hero count/ETA |
| Interaction | 48 dp minimum touch targets; primary trip action 56 dp high |
| Motion | 180–240 ms count/layout transitions; honor disabled/reduced animation |

Use tabular numerals where supported. Count updates change the numeral without changing card width. System dynamic color is an optional setting; risk semantics remain consistent. Validate contrast in both themes rather than assuming brand tokens satisfy every combination.

## 3. First run and permissions

### Flow

`Welcome → choose service area → create first route → preview commute → enable automatic assistance → permission checklist → dashboard`

Welcome copy: **「出門前知道有沒有車，抵達前知道有沒有位。」** Explain that YouCommuter is an independent assistant and does not perform rentals.

Do not require an account, home address, precise location, or background access merely to browse stations or create a route. A user can choose a city and search station names manually.

### Progressive permission requests

| Permission | Ask when | If denied |
|---|---|---|
| Notifications | User enables scheduled reminders | Keep in-app checks and show a persistent settings checklist item |
| Foreground precise location | User chooses Nearby or enables automatic assistance | Search/map selection and manual events remain available |
| Activity recognition | After explaining automatic borrow/return detection | Disable automatic inference; retain manual controls |
| Background location | After foreground permission, with a separate explanation and required OS settings flow | Automatic unattended detection is unavailable; offer user-started tracking |

“Automatic” is the recommended selected mode, not an assertion that permission has already been granted. Display actual capability as **自動偵測已啟用**, **需補齊權限**, or **手動模式**. Approximate-only location does not qualify for unattended station attribution.

Background explanation: **「在通勤時段辨識你是否已借車或還車，並自動切換站點資訊。詳細位置與感測紀錄保留在手機。」** Also explain that route geometry requests use the self-hosted routing service transiently when live tracking is enabled.

Never loop permission dialogs. “Not now” completes onboarding. A revoked permission updates capability immediately and stops dependent listeners/services.

## 4. Route creation and editing

Use a five-step wizard with a visible step indicator, Back, Next, and locally saved draft. Each step is independently editable from the final review.

### Step 1 — Name and schedule

- Name defaults to 上班; presets: 上班、回家、轉乘、自訂. Name length: 1–40 characters.
- One departure time per route; users duplicate a route for multiple distinct daily departures.
- Weekday chips default Monday–Friday; one selected day minimum unless route is manual-only.
- Reminder lead time defaults to 15 minutes; choices 5, 10, 15, 20, 30.
- Detection window defaults to departure minus 30 minutes through departure plus 60 minutes.
- Schedule uses Asia/Taipei explicitly; traveling with the phone does not silently move Taiwan commute times.
- Holiday policy: skip official days off by default. “Follow government working calendar” additionally enables makeup working days. Custom date exceptions override either policy.
- Show the next three scheduled occurrences, including why a date is skipped.

### Step 2 — Borrowing

- Search by station name, address, or district; city filter is explicit and remains visible.
- List is fully functional without a map or location permission.
- Each row shows station name, district/city, current relevant bike count, source age, and distance type.
- Distances from a known location say “步行約 N 分鐘” only when routed. Straight-line distances say “直線約 N 公尺”.
- Select one primary station and zero to five backups; exclude duplicates and incompatible bike systems.
- Recommend five nearby stations using pedestrian routes, not just straight-line distance.
- Reorder selected backups by drag handle, with equivalent Move up/down accessibility actions.
- Optional local-only departure anchor: select a map point or use the primary station. No address geocoder is required for v1.

### Step 3 — Returning

- Select one primary return station and zero to five backups.
- Recommend alternatives by usable dock supply and total travel impact.
- Optional local-only destination anchor can be selected on the map. If omitted, use the primary return station as the anchor and label extra walking as relative to that station.
- Preview bike-system compatibility. Cross-city routes may be saved; show an official-information link for cross-region rental conditions rather than calculating unverified fees.

### Step 4 — Assistance preferences

| Setting | Default | Allowed values |
|---|---|---|
| Low-bike threshold | ≤ 3 bikes | Integer 0–10 |
| Low-dock threshold | ≤ 2 docks | Integer 0–10 |
| Extra walking to borrow | 5 minutes | 3, 5, 10, 15 |
| Extra journey time at return | 5 minutes | 3, 5, 10, 15 |
| Backup source | Saved first, then nearby | Saved only; saved then nearby |
| Bike preference | Any | Any; prefer regular; prefer electric |
| Reminder mode | Departure + meaningful risks | Risks only; departure + risks; off |
| Detection | Automatic | Automatic; manual |
| Detection sensitivity | Balanced | Conservative; balanced; responsive |
| Tracking battery mode | Balanced | Battery saver; balanced; responsive |

Bike preference is soft. If electric counts are missing, say **「此地區未提供電輔車數量」**; do not treat total bikes as electric bikes. No “electric only” guarantee in v1.

“Saved only” is a hard candidate restriction. If every saved station is unsuitable, show the reason and an explicit “Search outside my backups” action rather than silently expanding the set.

### Step 5 — Review

Show schedule, primary stations, backup counts, walking/riding estimate, reminder preview, and detection readiness. Save succeeds offline into Room, with **「已儲存，等待同步提醒設定」** until backend acknowledgment. Do not claim reminders are active until registration is acknowledged.

Route editing does not modify an in-progress session: active sessions retain a route snapshot. Deleting or disabling a route cancels future reminders; the current session continues unless explicitly ended.

## 5. Commute dashboard by state

### A. Idle / next commute

- Header greeting and settings.
- Next route name, departure time/date, primary station pair, next reminder time.
- Main action: **查看這趟通勤**. Secondary: **立即出發**.
- Do not display out-of-context future availability counts hours before departure.
- Empty state: concise explanation and **新增通勤**.
- Multiple upcoming routes: earliest occurrence first, remaining routes collapsed below. Only one active trip per installation.

### B. Preparing / walking to borrow

```text
上班                         自動偵測已啟用
08:30 出發 · 預計 08:49 抵達還車站

借車  台電大樓
8 台                         步行約 3 分鐘
電輔 2 台                    1 分鐘前更新

              騎乘約 15 分鐘

還車  公司站
現在 7 格  →  抵達約 3 格
預估範圍 1–6 格              空位可能偏少

[ 查看較有把握的備用站 ]
[ 開始通勤 ]          已借車 · 今天不騎
```

The displayed arrival includes departure delay, walking, unlocking, and riding; the example numbers are illustrative. Tapping **開始通勤** starts user-visible tracking and enters Walking, not Riding. **已借車** confirms the actual borrow station and borrow time, then enters Riding immediately.

Use one primary recommendation. If risk is healthy, backups are collapsed. If risk is elevated, show the best eligible alternative and a “View all” link. Never switch automatically.

### C. Riding

```text
騎乘中                       自動判定 · 更正
公司站

             約 7 分鐘
             抵達還車站

現在 4 格  →  抵達約 2 格
預估範圍 0–4 格              建議查看備用站

公司東門站
抵達約 10 格 · 全程約多 2 分鐘
[ 這次改去東門站 ]

[ 我已還車 ]                 其他站點
```

- Remove the borrowing card completely.
- Keep large ETA, destination, dock availability, freshness, and one action.
- No animated charts or blocking full-screen prompts while riding.
- Inferred borrow has a persistent subtle correction link. Correcting it restores Walking and removes the tentative start time.
- Navigation action opens a route preview and an external map chooser; v1 does not implement spoken turn-by-turn navigation.
- A destination change shows a snackbar **「僅變更這次通勤」** and recalculates arrival/forecast for the new station before promoting it.

### D. Near destination / likely return

When near the target, emphasize current usable docks and the nearest eligible alternative. “Possible return” remains within the ride view until enough evidence supports completion; show **「你已還車了嗎？」** with **已還車 / 還在騎** when evidence is ambiguous.

If the user visits another compatible station, infer a return only when that station can be attributed unambiguously. Otherwise ask the user to select the actual station; do not assign the originally planned destination automatically.

### E. Completed

- Inferred: **「偵測到你可能已還車，已停止通勤追蹤。」**
- Confirmed: **「本次通勤已完成。」**
- Both: remind that official rental completion is checked on the bike/official service, not by YouCommuter.
- Show actual station pair, estimated or confirmed start/end, duration, and whether included in personal ETA.
- Actions: **確認這筆紀錄**, **更正**, and **我還在騎** for an inferred completion.
- “Still riding” is available prominently for 10 minutes; later edits remain available in history. Restoring live tracking requires a valid current session and foreground entry.
- No automatic confirmation after a timeout. Unconfirmed history never quietly becomes training data.

### F. Exceptional states

| Situation | UI behavior |
|---|---|
| Feed stale | Keep last observed count with timestamp; gray estimate; no healthy badge |
| Feed missing/unavailable | `—` count, reason, available alternatives, retry |
| Station temporarily closed | Closed label; disable recommendation; preserve saved selection for editing |
| Forecast building | “正在累積歷史資料”; current count and ETA remain useful |
| Forecast experimental | Approximate count/range with “資料仍在驗證”; no numerical shortage probability |
| ETA unavailable | Ask for an estimated duration; do not invent a road ETA from straight-line distance |
| Network lost | Cached state and manual events continue locally; queued synchronization indicator |
| Tracking interrupted | Show “追蹤已暫停” with resume/manual completion; never mark a ride completed solely due to silence |
| Session timeout | Move to Needs review and stop expensive tracking; no valid training record |
| Backend settings unsynced | Local route works; show which reminder change has not reached server |

## 6. Stations and analytics

### Map/list

Implemented map integration (2026-10-20): native, theme-aware Google Maps are the initial presentation in station browsing and the full-page station picker when configured. A build-time Android Maps key is required; without it, the map shows a concise unavailable state and the list switch remains available. A labeled map/list icon switches presentation without changing the query or region. Browsing offers bike/dock metric chips. Dense stations cluster; tapping a cluster zooms in. Selected stations are excluded from clustering. Map taps open a compact inline preview; choosing a route station requires the Select button. Blank-map taps do not dismiss the preview. Invalid coordinates are omitted, and non-fresh/closed/unknown counts display “—” on markers. Map loading/failure supports retry and leaves the list switch available. Google attribution and fit/zoom controls remain visible; camera padding reserves space for the controls. Real basemaps, inline previews, explicit selection and the commute flow were verified on API 33, including dark mode at 130% font size. See `e2e-artifacts/REPORT.md` for evidence and limits.

The commute embeds the selected borrow/return stations and saved backups in a compact map. Before borrowing, bike markers belong to borrowing stations and dock markers belong to returning stations; after borrowing only return markers remain. Saved backup taps update the same selection as the station chips. No road polyline, route duration, turn-by-turn navigation, device-location indicator or proximity inference is implied by these maps. Inventory refresh is explicit; no background location tracking is introduced.

Default to the last chosen view. Segmented control: **借車 / 還車** determines marker counts and list ordering. Marker numbers are current observations unless an arrival time is explicitly selected. Do not mix current and predicted counts within the same map layer.

Cluster dense regions; selected station and session destination stay visually distinct. Search results use a debounced 300 ms query; cancellation prevents older responses replacing newer queries. Missing map tiles do not block the list.

### Station detail

Show station status; total/regular/electric bikes where known; return docks; capacity; timestamp; favorites; directions; and “Use for this trip”.

Analytics include:

- Last 60 minutes of observed counts and up to 90 minutes of available forecast; observed and forecast portions use distinct line styles.
- Median and P10–P90 prediction band; gaps remain gaps.
- Day-of-week/time selector with recent historical distribution.
- “Based on N distinct days” and source cadence, never raw poll count as confidence.
- Shortage risk only when the forecast is validated; its threshold wording is **「剩餘 ≤ 2 格的估計機率」**, not “below 2” for an inclusive event.
- Clear distinction between full-station probability (`docks == 0`) and low-dock probability (`docks <= user threshold`).

Charts have a text summary and accessible table alternative. Changing analytics filters does not modify a commute schedule.

## 7. Notifications

One logical notification per occurrence/session, updated in place. Channels: departures, important station risks, active tracking, and service/accountless setup issues. Users can control sound/vibration through Android settings.

| Context | Content | Actions |
|---|---|---|
| Departure | Route, departure offset, borrow count, return count → arrival estimate | 已借車; 今天不騎; open |
| Riding | Destination, arrival ETA, current docks, major risk | 我已還車; open |
| Risk change | Current target and one better alternative with extra time | open alternative review |
| Inferred completion | Tracking stopped, actual station if known, confirmation status | 確認; 我還在騎 |

Station switching from a risk notification opens the app's compact review with refreshed conditions; an old notification does not silently reroute a current trip. Manual borrow/return actions can be handled as idempotent commands without a network round trip.

Default lock-screen content is generic: **「通勤資訊已更新」**. Detailed station names are an opt-in setting. High-priority push payloads include enough content to render without an additional fetch.

Risk-only mode has no routine healthy departure notification and no high-priority silent push used as a substitute. Risk alerts follow the debounce/cooldown policy in the algorithm specification.

## 8. Widgets

Use Jetpack Glance; sizes are responsive rather than fixed pixel layouts.

- Compact: next route, departure time or active ETA, current target count, observation age.
- Expanded: both station cards before borrowing; destination and best backup while riding.
- Configuration: automatic current route or one pinned route.
- Actions: open trip; refresh; confirmed borrowed/returned action with the same reducer as notifications.
- Background refresh is best effort; widgets are not second-by-second live surfaces. Update on app data changes, push receipt, explicit refresh, and a 30-minute periodic request.
- Every count carries freshness. Tapping Refresh never resets an old source timestamp to “now”.

## 9. History, settings, and data control

History is local, newest first, grouped by date. Filters: all, confirmed, inferred, needs review. Editing station/time previews the impact on duration and eligibility before saving. Deleting a ride recomputes the personal duration model.

Provide a local export through Android's system document picker and an explicit import with schema validation. Installation credentials and FCM tokens are never exported. Export format is versioned JSON; CSV ride export is a secondary option. No automatic cloud backup of sensitive local app storage.

Settings expose all route defaults, reminder capability, current service coverage, data freshness, automatic detection health, appearance/language, and privacy. A single “Delete YouCommuter data” flow clears local history and requests deletion of installation-owned server state; if offline, show pending server deletion until acknowledged.

## 10. UX acceptance checks

- A new user can save a manual route without granting location or notifications.
- A returning user can determine the next station and arrival risk from the first screen without scrolling on a 360 × 800 dp viewport at normal text size.
- Borrow confirmation removes borrowing information in one state transition; completion stops active tracking.
- Every inferred state can be corrected without editing the permanent route.
- TalkBack reads station name, metric type, count, freshness, forecast status, then action; it does not read a bare sequence of numbers.
- At 200% font scale, critical actions and counts remain reachable without clipping; content can scroll.
- No critical meaning depends on color, drag gestures, map visibility, or motion.
- Loading retains stable card geometry. Errors preserve existing user input and show a concrete retry/recovery action.
