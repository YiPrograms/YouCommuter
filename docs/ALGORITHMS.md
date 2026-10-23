# Algorithms and behavioral rules

All constants below are v1 defaults, not empirically proven optimal parameters. Store them in versioned configuration, test them with recorded fixtures, and change them only with a new algorithm version and validation report.

## 1. Time, freshness, and eligibility

Use UTC instants for transport/storage and Asia/Taipei for schedules, weekday grouping, and calendar dates. Use monotonic elapsed time for sensor duration comparisons within the same device boot. A manual clock change must not create a negative ride duration.

For trustworthy observation timestamp `t_obs`, current time `t_now`, and configured publication interval `c`:

```text
age = max(0, t_now - t_obs)
fresh_limit = max(120 seconds, 2*c + 30 seconds)
stale_limit = max(600 seconds, 3*c)

fresh:       age <= fresh_limit
aging:       fresh_limit < age <= stale_limit
stale:       age > stale_limit
unavailable: missing metric, invalid sample, or missing usable timestamp
```

Future source timestamps more than 60 seconds ahead of backend time are invalid; smaller skew is retained with a quality flag. Provider-only timestamps never qualify for a validated forecast. A fresh count is still only an observation, not a guarantee of a bike or dock.

Only fresh, operating, compatible stations qualify for automatic recommendations. Aging data remains visible but has unknown forecast risk and does not outrank a fresh candidate. Stale/closed/unknown stations can be inspected but not recommended as available.

The app reads YouBike directly and optionally calls a user-selected prediction endpoint. Public metadata compatibility uses `stationVersion`; database epochs remain private. Current YouBike data uses `timestampQuality=feed_snapshot`: `t_obs` is the provider inventory-snapshot time, not a controller or rental event. The implemented experimental estimator is `analog-delta-feed-v1` and reports `FEED_SNAPSHOT_TIMESTAMPS`; shortage probabilities remain null and risk remains unknown. Missing feed times stay `provider_only`. The validated-model rules below remain acceptance targets. Route preferences select total, regular or electric bikes for thresholds, backup ranking and forecast requests.

## 2. Occurrence scheduling

Each route has one departure slot and an optional weekly schedule. Generate local dates, resolve them in Asia/Taipei, then convert to UTC.

Precedence, highest first:

1. Disabled route: no automatic occurrence.
2. Explicit date exception: skip, force, or custom departure overrides calendar/weekday rules.
3. “Follow government working calendar”: use that day's official working/day-off classification when available.
4. Otherwise, selected weekdays apply, with official days off suppressed when “skip holidays” is enabled.
5. Missing calendar coverage falls back to selected weekdays and shows a calendar-unavailable status; do not guess that an unknown date is a holiday.

Download/version the official [government working calendar](https://data.gov.tw/dataset/14718?page=3). Calendar updates invalidate unsent future jobs and recompute previews. Previously sent occurrences are not re-sent because the calendar version changed.

Default reminder is departure minus 15 minutes. Detection eligibility is departure minus 30 minutes through departure plus 60 minutes. Crossing midnight is resolved by the occurrence's scheduled local date, not the phone's current date alone.

When eligible routes overlap, preserve an existing active session. Without one, select the route whose borrowing group matches the evidence and whose departure is closest in absolute time. If multiple routes match and departures are within ten minutes of each other, ask which commute; do not silently choose a destination.

## 3. Trip state machine

`IDLE` is a dashboard condition. Persisted session phases are:

```text
PREPARING → WALKING_TO_BORROW → RIDING → NEAR_RETURN → COMPLETED
     └──────────────→ RIDING ───────────────────────→ COMPLETED

PREPARING / WALKING_TO_BORROW → SKIPPED or CANCELLED
Any nonterminal phase → NEEDS_REVIEW on unrecoverable continuity/timeout
Inferred COMPLETED → RIDING through explicit undo
NEAR_RETURN → RIDING when continuing away from the station
```

### Transition table

| Current state | Event/guard | Next state and effect |
|---|---|---|
| No session | Occurrence opened or explicit start | PREPARING; snapshot route configuration |
| No session | Borrowing geofence event matches one eligible route | PREPARING; create local occurrence/session snapshot and evaluate buffered evidence |
| PREPARING | Start commute | WALKING_TO_BORROW; user-started location tracking if permitted |
| PREPARING / WALKING | Confirm borrowed | RIDING; confirmed station/time; hide borrow cards |
| PREPARING / WALKING | Strong borrow inference | RIDING; inferred start interval; destination monitoring |
| RIDING | Target-area evidence or reliable routed remaining time ≤ 3 minutes | NEAR_RETURN |
| NEAR_RETURN | Clear cycling continuation outside destination area | RIDING |
| RIDING / NEAR_RETURN | Confirm returned | COMPLETED; confirmed endpoint; stop tracking |
| RIDING / NEAR_RETURN | Strong return inference | COMPLETED, inferred; stop tracking, offer undo |
| RIDING inferred | “Not borrowed” correction | WALKING; remove inferred start and duration eligibility |
| COMPLETED inferred | “Still riding” undo, no newer active session | RIDING; remove inferred endpoint; resume through foreground UI |
| PREPARING / WALKING | Skip this occurrence | SKIPPED; cancel alerts for occurrence |
| Nonterminal | Explicit cancel | CANCELLED; stop tracking, retain no valid ride sample |
| Nonterminal | Tracking exceeds 180 minutes | NEEDS_REVIEW; stop expensive tracking; never fabricate return |
| PREPARING / WALKING | Departure + 60 minutes passed with no ride | CANCELLED with `occurrence_expired` reason |

Terminal records can be edited later, but historical edits do not automatically launch a foreground service. A destination change is an event within a phase, not a new trip.

### Reducer contract

```kotlin
fun reduce(
    state: SessionState,
    event: CommuteEvent,
    context: EvaluationContext
): Transition // newState, persistedEvents, requestedEffects
```

Events carry UUID, origin, received instant, event instant or interval, boot identity where applicable, and expected session revision. Event application is idempotent. Manual actions take precedence over inference and suppress contradictory auto-transitions for three minutes.

Persist an event revision and the resulting state together. Effects such as notification updates, geofence changes, or API synchronization execute after commit and are independently retryable. Duplicate effects must not produce duplicate sessions or alerts.

## 4. Borrow/return inference

### Implemented Bluetooth borrowing rule

The owner confirmed that the named `EYB` field sample was an ordinary bike and chose this rule on 2026-10-21: while a commute is Preparing or WalkingToBorrow, a **new ACL connection** with a cached name starting with the case-sensitive prefix `EYB` immediately applies ConfirmBorrowed at the currently selected borrow station. Do not constrain the suffix, infer electric-bike type, wait for disconnection, or require location/activity evidence for this path. Missing/unrelated names and already-connected snapshots do not trigger it. This is an accepted product heuristic, not an authenticated rental result from YouBike.

The foreground observer starts from the resumed app with Bluetooth permission, continues while using the official app, and stops on borrowing, cancellation, session replacement or Bluetooth off. It is nonsticky and stops with the task; reopening the app can resume a pending commute. Commands target both the session ID and revision, so queued/duplicate callbacks cannot mutate a replacement session or override a newer action. The same Room transaction persists the normal borrow transition. Manual borrowing and returning remain available; disconnection never completes a ride.

### Future location/activity inference

The remaining rules in this section describe future location/activity inference. They do not gate the implemented Bluetooth rule above.

### Evidence model

Use activity transitions, geofence encounters, and optional recent accurate location fixes. Android's [Transition API](https://developer.android.com/develop/sensors-and-location/location/transitions) supplies activity changes; it does not report the probability of a rental. Scores below are heuristic evidence totals.

Never use a public station count decrement/increment to attribute an individual rental. Never treat geofence presence alone, motion alone, or elapsed time alone as a rental.

Bluetooth mechanics and the owner's chosen connection rule are recorded in the [app-borrow research](RIDE_DETECTION_RESEARCH.md). The inspected official app disconnects during unlock success, so disconnection supplies no return-completion evidence. The connection rule does not add confidence points to this future location/activity model. The debug field-observation tool records system links and manual test markers separately.

Default passive fence radius is 150 m. A recent location fix is usable for station attribution when age ≤ 30 seconds and reported horizontal accuracy ≤ 50 m. A confidently outside fix satisfies `distance - accuracy > 150 m`; a confidently near fix satisfies `distance + accuracy <= 150 m`.

Overlapping fences create a candidate set. Prefer a candidate only if an accurate fix or stationary encounter distinguishes it: nearest candidate within 60 m and the next candidate at least 40 m farther away. Without that separation, keep station identity ambiguous. A solitary geofence candidate can be used with geofence-only evidence, with wider timestamp uncertainty.

Retain relevant transitions for a rolling ten-minute evidence window. Event time, not delivery time, determines order. A delayed event never reverses a newer manual decision. Reject contradictory/obsolete events and record diagnostic reason codes locally.

### Borrow rules

Hard gates:

- Route occurrence is eligible or the user explicitly started the session.
- A unique compatible borrowing station was encountered.
- A stop/dwell occurred at that station before cycling departure.
- `ON_BICYCLE` entered and has not subsequently exited or been contradicted by `IN_VEHICLE`.
- Departure from that station is evidenced by a geofence exit or a confidently outside fix.

Score after hard gates:

| Evidence | Points |
|---|---:|
| Unique station encounter and qualifying dwell | 25 |
| Sustained cycling transition | 35 |
| Departure from station | 25 |
| At least two accurate fixes, ≥ 20 s apart, consistent with 5–35 km/h travel | 15 |

| Sensitivity | Auto threshold | Minimum dwell | Minimum cycling duration |
|---|---:|---:|---:|
| Responsive | 80 | 15 s | 20 s |
| Balanced | 85 | 20 s | 45 s |
| Conservative | 95 | 45 s | 90 s |

Hard gates are never bypassed by adding points. For passive operation, dwell/exit and transition timestamps can satisfy Balanced without continuous GPS. A duration rule is evaluated when a callback or permitted follow-up job runs; it is not a promise of a callback at the exact threshold.

Partial evidence with a unique station and cycling, but a missing dwell/exit, produces a single **“Possibly borrowed?”** prompt. Ambiguous station identity opens a short candidate selector. Repeated uncertain evidence does not create repeated prompts within five minutes.

`IN_VEHICLE` evidence invalidates a pending borrow inference. It does not terminate a confirmed ride; instead it marks the ride for review if sustained for more than two minutes.

Start-time estimate: use the cycling transition nearest the station departure. Preserve an uncertainty interval bounded by the last at-station noncycling evidence and first departing-cycling evidence. If either boundary is missing, mark time uncertain; do not invent a zero-width timestamp.

### Return rules

Hard gates:

- Session is RIDING or NEAR_RETURN and has a known borrow event.
- A compatible return station is uniquely attributable.
- Cycling exited around the station encounter.
- No cycling re-entry or vehicle transition contradicts completion.

Score:

| Evidence | Points |
|---|---:|
| Unique return-station encounter | 30 |
| Cycling exited during encounter | 25 |
| Walking after cycling exit | 25 |
| Leaving that station while walking | 20 |
| Still only, without walking | 10 instead of the walking points |

Automatic completion requires score ≥ 90 and at least 90 seconds without cycling. Therefore, merely waiting at a red light, standing beside a station, or stopping to look for docks does not auto-complete. Responsive/Conservative affect borrow detection only; return completion keeps the conservative common rule.

If walking/station evidence exists but no walking departure, ask whether the user returned the bike. If several stations overlap, ask for the actual station. A nearby unplanned station can be considered only from cached/fetched compatible station metadata and the same attribution rules.

Estimate return time from the transition between station-local cycling and noncycling evidence; retain the interval. Stop active tracking on inferred completion and keep the completion notification/correction affordance. Confirmation validates the record; it does not assert a successful official rental transaction.

### Sensor and battery schedule

| Mode | Passive period | User-started active ride |
|---|---|---|
| Battery saver | Geofence/activity transitions | Balanced-power location every 30 s requested |
| Balanced | Geofence/activity transitions | Location every 15 s requested |
| Responsive | Geofence/activity transitions | Location every 10 s requested |

Near a candidate station, temporarily request ten-second fixes for up to two minutes while an already valid foreground service is active. Requests are hints to the OS, not delivery guarantees. No station polling occurs continuously on idle phones; the backend collects shared data.

## 5. Personal ride-duration model

### Training eligibility

A record is eligible only if:

- The user explicitly confirmed or edited both endpoints/stations, including confirming the complete record after inference.
- Borrow and return stations are known and compatible.
- Start/end uncertainty intervals are each at most 120 seconds wide, or the user explicitly supplied corrected times.
- Duration is between one and 180 minutes.
- No unresolved interruption, overlapping session, vehicle segment, or user-marked pause exists.

Do not remove a legitimately slow ride merely because it is an outlier. Suspected bad timing is flagged for review; excluding a ride is an explicit eligibility decision, not silent trimming to improve metrics.

Cohort key: actual borrow station, actual return station, bike class (`regular`, `electric`, `unknown`), and departure two-hour band. Use matching weekday with weight 1 and other days in the same workday/day-off class with weight 0.5. If the exact time cohort has fewer than five records, broaden to all times for that station pair. Do not merge different station pairs as though travel time were interchangeable.

Use at most 60 confirmed rides from the last 90 days. Recency weight is `2^(-ageDays/30)`; one trip is one sample. Convert durations to one-minute bins for local computation.

### Cold start and blending

Let `B` be Valhalla bicycle travel time plus 30 seconds for docking. Where available, a user-entered typical duration replaces B. Default regular-bike speed input is 15 km/h and electric-bike input is 18 km/h; these are routing assumptions to be refined by personal history, not measured speeds.

The routing prior is the explicit five-point distribution:

```text
duration multiplier:  0.70  0.85  1.00  1.20  1.50
probability:          0.05  0.20  0.50  0.20  0.05
```

Clamp durations to at least one minute. Label this as a routing estimate. Its spread is a product prior, not an empirically calibrated personal confidence interval.

For effective confirmed sample count `n`, blend probability distributions:

```text
w_personal = min(0.9, n / (n + 5))
P(D) = w_personal * P_personal(D) + (1 - w_personal) * P_prior(D)
```

Do not average percentiles or means and call the result a distribution. With no routing path and no personal/user duration, ETA is unavailable; prompt for a duration instead of inferring a road journey from straight-line distance.

### Before borrowing

Arrival at the return station is:

```text
H = wait_until_departure + walk_to_actual_borrow_station + unlock_time + D
```

- Waiting is deterministic from the user's selected departure, floored at zero.
- Walking prior uses the routed walking duration with multipliers 0.8/1.0/1.3 and weights 0.2/0.6/0.2.
- Unlocking prior is 30/60/120 seconds with weights 0.2/0.6/0.2.
- If origin is unknown, use the saved walking-duration summary; default zero only when origin is explicitly the borrow station.
- Borrow-station availability is forecast at the walking arrival, before unlocking; return-station availability uses the complete H.

Changing a borrow station changes both walking and station-pair riding distributions. It must invalidate return forecasts too.

### During riding

Without fresh location, condition on the fact that the ride has not ended. For elapsed ride time `e`:

```text
P(R = d-e | D > e) = P(D=d) / sum_{u>e} P(D=u), for d > e
```

Never subtract elapsed time from the original median and clamp forever at zero. If no probability mass survives, ETA becomes overdue/unknown until location or user input supplies new evidence.

For an inferred borrow-time interval, represent its lower bound, midpoint, and upper bound with weights 0.25/0.50/0.25. Combine this start-time distribution with D, retain only combinations whose finish is after now, and normalize the remaining mass. This propagates start uncertainty instead of treating a delayed sensor callback as an exact start.

With a location fix no older than 30 seconds and accuracy ≤ 50 m, request a remaining bicycle route at most once per minute or after moving 100 m, whichever triggers first, with a minimum 15-second interval. Personalize its time by `clamp(personalPairMedian / B, 0.6, 1.8)` when at least five eligible rides exist, using the same original-pair baseline B defined above.

Use a mixture of 70% live-routing remaining-duration prior and 30% conditional historical remaining distribution. If one component is unavailable, use the other with its provenance. Recompute after destination changes; never use A→X duration unchanged for A→Y.

Serialize arrival distributions as absolute instants. To bound payloads, combine one-minute bins into adjacent two-minute groups and represent each group by its probability-weighted mean time, preserving total mass. Limit to 91 points. Keep calculation full precision until UI rounding.

ETA shown to the user is P50 rounded to the nearest minute, with a minimum “less than 1 minute” display rather than a negative countdown. Riding-time model accuracy is evaluated separately from dock forecast accuracy.

## 6. Canonical station history

### Five-minute sampling

At each five-minute Taiwan boundary `b`, select the latest valid source observation with `observedAt <= b`. Its age at b must be at most `min(2*c + 30 seconds, 600 seconds)`.

Do not choose a later observation because it is closer; that would introduce future information into backtesting. Do not interpolate across missing source periods. Preserve validity, source age, service state, metric availability, and station epoch at every bin.

Histories use observations of stock, not inferred rentals/returns. A constant zero count does not mean no demand occurred. v1 deliberately models future available stock; it does not estimate latent demand or operator redistribution separately.

### Supported horizons

Use horizon knots `0, 5, 10, ..., 90` minutes. The zero-horizon distribution is a point mass at the current valid observed count. For positive horizons, learn changes between valid canonical endpoints, requiring an operating, uninterrupted, same-epoch path through intermediate bins.

No forecasts beyond 90 minutes in v1. If any meaningful input arrival point exceeds 90 minutes from the observation, return `UNSUPPORTED_HORIZON`; the caller shows current counts and unsupported arrival horizon. Never drop long-arrival probability mass to make the result look safer.

## 7. Availability distribution estimator

### Historical analogs

For station epoch s, metric M, current observed count x, capacity C, observation time t, and knot horizon h:

```text
delta_i(h) = M_i(start_i + h) - M_i(start_i)
y_i(h) = clamp(x + delta_i(h), 0, C)
```

Use the past 84 completed local dates. Candidate historical starts lie within ±15 minutes of t's local time, on the five-minute grid. Historical horizons can cross midnight; fetch the next date's series rather than wrapping inside the same day.

Filter candidates to:

- Same station epoch and valid metric/capacity.
- Same day class: ordinary working day, ordinary weekend/day-off, or public holiday. Explicit makeup workdays belong to working-day class. Public holidays remain separate.
- No outage, closure, epoch change, or invalid count along the sampled interval.
- Starting stock ratio within 0.35 of today's stock ratio: `abs(M_i/C - x/C) <= 0.35`.

All changes, positive and negative, are retained. Operational rebalancing remains part of observed station behavior unless the source flags invalid data.

Candidate similarity:

```text
timeWeight = 1 - abs(offsetMinutes)/20
stockWeight = exp(-abs(M_i/C - x/C)/0.20)
withinDayWeight = timeWeight * stockWeight
```

Normalize candidate weights within each historical date. The entire date receives:

```text
recencyWeight = 2^(-ageDays/28)
weekdayWeight = 1.0 if same weekday else 0.35
dayWeight = recencyWeight * weekdayWeight
```

Thus seven nearby bins from one historical Tuesday contribute one day's weight, not seven independent Tuesdays. Build a weighted integer histogram over y values; divide by total weight to obtain a PMF.

Effective support is calculated from day weights:

```text
n_eff = (sum(dayWeight))^2 / sum(dayWeight^2)
```

Track distinct dates, effective dates, same-weekday dates, missing fraction, and state-match coverage independently. Do not substitute raw observation count for these measures.

### Non-knot horizons

For `h0 <= h <= h1`, mix the two PMFs:

```text
alpha = (h-h0)/(h1-h0)
P_h = (1-alpha)*P_h0 + alpha*P_h1
```

This preserves nonnegative probabilities and total mass. Do not linearly interpolate quantile values and then infer a tail probability from them. If either required positive-horizon component lacks support, that arrival point is unavailable.

Time-of-day smoothing already occurs through the analog window; do not additionally multiply the sample size by adjacent buckets.

### Combining arrival uncertainty

For the API's absolute arrival instants `a_j` with weights `q_j`:

```text
h_j = a_j - observation.observedAt
P_arrival(k) = sum_j q_j * P_h_j(k)

P_empty = P_arrival(0)
P_low(theta) = sum_{k=0..theta} P_arrival(k)
quantile(p) = smallest k whose CDF >= p
```

This includes source age automatically. It also accounts for tomorrow's arrivals using the correct future timeline rather than querying the current weekday as if midnight never occurred.

The mixture assumes the supplied personal travel-time distribution is independent of station changes conditional on the modeled calendar/time context. Weather and disruption can violate that assumption; this is a known v1 limitation addressed by validation and uncertainty labels, not hidden precision.

### Status and cold start

| Status | Condition | Public output |
|---|---|---|
| unavailable | Missing/stale/nonoperating metric, incompatible epoch, unsupported horizon | Current valid observation where available; no prediction |
| building | Fewer than 7 distinct usable dates or effective support < 5 | Current counts + “collecting history” |
| experimental | ≥ 7 dates and effective support ≥ 5, but validation/support gate unmet | Approximate median/P10–P90; null probabilities, unknown risk |
| validated | ≥ 14 dates, effective support ≥ 10, ≥ 3 same-weekday dates, fresh trusted source, all required calibration gates pass | Median/range, shortage probabilities, risk band |

For a multi-horizon mixture, use the weakest required component status and minimum support. A small-weight unsupported component is still represented as missing support; do not remove it silently.

A feed with no history does not receive a fabricated “50% risk”. Numeric probabilities are null until justified. With experimental/building forecasts, backup recommendations use fresh observed availability and explicitly say the arrival risk is unknown.

## 8. Calibration and forecast evaluation

Maintain calibrators by region, metric, and horizon band: `(0,15]`, `(15,30]`, `(30,60]`, `(60,90]` minutes. Fit a monotonic isotonic mapping from raw predicted CDF probability to observed binary outcome, using thresholds 0 through `min(10, capacity)`. Weight each station/issue-time forecast equally across its threshold examples so a larger capacity does not dominate.

For each component forecast, transform its CDF with the mapping, enforce `F(-1)=0` and `F(C)=1`, and difference neighboring CDF values to recover a valid PMF. This maintains monotonicity across thresholds. Derive displayed quantiles from the same transformed distribution used for risks, then mix arrival components.

Calibration and evaluation must be strictly forward in time:

1. At least 28 days of history for initial model construction.
2. Next 14 days generate rolling-origin forecasts; each issue time uses only earlier observations. Fit calibrators on these predictions/outcomes.
3. Next 14 untouched days evaluate the frozen calibration version using rolling-origin base distributions.
4. Refresh candidate calibrators weekly; promote only after an untouched evaluation window. Keep the prior active model until promotion.

The calibration artifact is invalid if its provider definition, epoch policy, metric normalization, or algorithm version changes. No random train/test split across adjacent minutes.

Validation gates for a region/metric/horizon band:

- At least 1,000 forecast cases spread over 14 evaluation dates and at least 20 stations, or all eligible stations if the region has fewer than 20.
- For the requested threshold and empty-event probability, at least 100 positive and 100 negative evaluation outcomes. Deterministic thresholds at/above physical capacity are handled directly as probability one.
- Brier score no worse than the persistence baseline; target at least 10% relative improvement when baseline Brier > 0.01.
- Expected calibration error ≤ 0.10 using ten equal-frequency probability bins.
- Median count MAE no worse than persistence; nominal 80% intervals have empirical coverage between 70% and 90%.
- Bootstrap uncertainty by date, not by individual adjacent forecasts. Report station-specific performance and do not hide a failing region behind national averages.

These are release gates, not promised performance. If evidence is insufficient or a gate fails, keep that band experimental. User-specific ETA/arrival mixtures additionally receive replay evaluation once confirmed ride data exists locally; aggregate QA does not authorize uploading private ride histories.

Evaluate current-count persistence as `predicted future count = observed count`, seasonal historical level, and the proposed delta model. Store count MAE, interval coverage/width, Brier score, calibration error, false-healthy rate, and alert volume.

Do not use observed post-arrival counts, future capacity metadata, or today's future data during replay. Capacity/closure transitions require issue-time metadata snapshots.

## 9. Risk semantics and backup selection

### Risk band

For a validated forecast with configured inclusive low-count threshold theta:

```text
healthy: P(count <= theta) < 0.15
caution: 0.15 <= P(count <= theta) < 0.40
high:    P(count <= theta) >= 0.40
unknown: forecast is not validated
```

Current zero supply triggers an explicit **current unavailability** warning regardless of predicted recovery. Keep that warning separate from future-risk color. A closed station is ineligible, not a 100%-risk forecast.

### Candidate generation

1. Include the selected primary and ordered saved backups.
2. Evaluate saved candidates first. If none is eligible and healthy (or above the current-count threshold when forecast risk is unknown), and policy permits nearby discovery, query up to 20 candidates within 2 km of the borrowing origin or return destination anchor. Explicit “Other stations” browsing can always request discovery.
3. Filter by operating status, compatible system, fresh usable data, positive current resource count, and routing reachability. A selected target currently at zero stays visible for explanation, but is not promoted as an available alternative based solely on predicted recovery.
4. Route candidates and enforce the user's maximum extra time. If none qualify, an explicit user action can expand to 5 km or relax the time bound for this session.
5. Forecast each candidate at its own arrival distribution. Never reuse the original destination's ETA for an alternative.

The 2 km radius is a computational prefilter, not a walking-time claim. The actual hard bound is the routed extra time. The “saved only” policy never adds outside stations automatically.

### Travel cost and utility

Before borrowing, compare walking to each borrow station plus riding to the chosen return station plus final walking. During riding, compare remaining riding plus walking from the candidate return station to the destination anchor. Include unlocking consistently on all pre-borrow alternatives; omit it during riding.

For validated candidates:

```text
cost_minutes = expected_remaining_journey_minutes
             + 12 * P(resource_count == 0)
             + 4  * P(resource_count <= threshold)
             + preference_penalty
```

The 12- and 4-minute terms are explicit product utility weights; they are not measured expected delays. `preference_penalty` is 0.25 minutes per saved-backup rank, 1.5 minutes for an automatically discovered candidate, and 1 minute for a known bike-type preference mismatch.

For “prefer electric/regular”, recommend a fresh station with a known positive preferred-type count when it is within one minute of the best otherwise-eligible candidate. Unknown subtype counts do not count as a match. Do not bypass detour limits or worse risk just to meet a soft vehicle preference.

Only compare numeric utility among candidates with validated risk. If the primary is validated high risk, an alternative with unknown risk may be shown as **“currently more bikes/docks; arrival risk unknown”**, not “safer”.

When predictions are unavailable for the comparison, use a deterministic current-data ordering: count above threshold first, then smallest total travel time, then larger observed count, then saved priority, then station ID. Label the ranking as based on current availability.

### Recommendation stability

- Keep the selected station until the user changes it.
- Surface a replacement when the current target is high risk or currently unusable and the alternative reduces validated low-resource probability by at least 0.15 or has clearly better current supply in current-only mode.
- Require two successive distinct source observations before a nonurgent suggestion changes.
- A newly closed or observed-zero target can trigger immediately if an eligible alternative exists.
- Once a suggestion is displayed, hold it for at least two minutes unless it becomes ineligible.
- Never describe an alternative as “more reliable” if its probability, freshness, or routing support is weaker.

## 10. Alert policy

The backend and phone use the same versioned risk-policy fixtures. The phone owns phase-sensitive suppression; the backend owns shared-data evaluation.

- Departure + risks mode: one scheduled departure notification, then meaningful changes only.
- Risks-only mode: no notification for healthy routine preparation; alert only for an eligible risk/current-unavailability event.
- Alert on entry into high risk after two distinct source observations, or immediately for closure/current zero.
- Caution appears in-app but does not generate a separate audible risk alert by default.
- Re-alert only for a newly selected target, a transition to unavailable, or a materially better alternative after a ten-minute cooldown.
- Recovery to healthy requires low-resource probability < 0.10 across two observations; silently update the existing notification.
- At most three risk alerts per occurrence by default; closure/zero alerts still respect per-station ten-minute deduplication. The initial departure reminder is separate.
- Suppress borrowing alerts in RIDING/NEAR_RETURN and all occurrence alerts after SKIPPED/CANCELLED/COMPLETED.
- Never use repeated high-priority silent pushes for periodic widget refresh or geofence setup.

## 11. Conformance examples

| Input | Expected result |
|---|---|
| Source docks = null | Unknown, not zero; no derived dock prediction |
| Capacity 20, current docks 8, historical delta -3 | Uncalibrated sample count 5 |
| Capacity 20, current docks 2, delta -5 | Sample clips to 0; retain zero mass |
| Current docks 8, delta +4 | Sample count 12; increases are not discarded |
| Histogram `{0:0.10, 1:0.15, 2:0.25, 5:0.50}`, threshold 2 | Empty probability 0.10; low-count probability 0.50; P50 = 2 |
| 15-min low risk 0.20, 20-min low risk 0.60, arrival 17 min | PMF interpolation gives low risk 0.36 before any separate calibration |
| Arrival mixture 25% at 10 min with risk .1, 75% at 20 min with risk .5 | Combined risk .4 |
| Observation 08:25, now 08:30, arrival 08:45 | Forecast horizon 20 min, not 15 min |
| 100 repeated polls from one Tuesday | At most one independent historical date |
| Two near-identical stations within overlapping fences | No automatic exact station attribution |
| Cycling past a station without dwell | No borrowed transition |
| Stopped cycling at a red light beside destination | No automatic return without walking departure |
| User says “not borrowed”, then delayed cycle event arrives | Preserve correction and suppression window |
| App killed while riding | Restore/review session; do not invent a return |
| Destination changes X → Y | Recalculate route, arrival PMF, forecast; permanent route unchanged |
| ETA distribution has mass beyond 90-min station horizon | Unsupported forecast, no truncation/renormalization |

## 12. Deliberately deferred algorithms

Weather/event models, immediate trend correction, station-to-station demand coupling, crowdsourced travel-time priors, route-choice learning, and machine-learning classifiers are v2 candidates. v1 records the evaluation evidence needed to decide whether they improve outcomes; it does not introduce a Python service or undocumented personalization logic now.
