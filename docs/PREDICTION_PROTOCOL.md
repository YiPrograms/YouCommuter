# Prediction service protocol v1

[OpenAPI](../api/openapi.json) and [the shared response fixture](../fixtures/forecast-batch.json) are normative. Any self-hosted or public service can implement this interface; Android does not require a YouCommuter account.

Only the current format is accepted. Include all declared fields, using explicit nulls where allowed. Unrecognized fields or enum values are rejected; no older response formats or renamed-field aliases are supported.

## Endpoint and credentials

Users configure the complete HTTPS endpoint, conventionally `https://host/v1/forecasts:batch`. The app sends POST to that exact URL. It neither appends a path nor registers an installation. If configured, the API key is sent as `Authorization: Bearer <key>` only to that service. Redirects are rejected. Cleartext local HTTP is debug-only; release requires HTTPS.

A blank endpoint disables predictions. Requests time out after 12 seconds, response bodies are bounded to 1 MiB, and the app retains official inventory when predictions fail. Provider changes discard pending results and posted prediction notifications.

## Request

```json
{
  "items": [{
    "stationId": "youbike:500101001",
    "metric": "rent_total",
    "arrival": {
      "points": [{"at": "2026-10-20T01:15:00Z", "probability": 1.0}],
      "source": "user_estimate",
      "distinctConfirmedRides": 0
    },
    "lowCountThreshold": 3
  }]
}
```

- Batch: 1–12 items; body at most 128 KiB.
- Metrics: `rent_total`, `rent_regular`, `rent_electric`, `return_docks`.
- Arrival points: 1–91 strictly increasing future instants; finite nonnegative probabilities sum to one within 1e-6. The current model supports horizons up to 90 minutes measured from the observation, including source age.
- Threshold: inclusive integer 0–10. This is a public inventory query, not a saved route or account mutation.

Return one entry per input in the same order, echoing station, metric, arrival and threshold. Each entry includes status, median/P10/P90 where supported, observation provenance, support, model version, generation and validity times. See the complete fixture rather than interpreting missing estimates as zero.

`building` and `unavailable` are successful item-level results without numerical estimates. Unknown stations do not hide healthy batch entries. The implemented estimator reports experimental counts and unknown risk; probabilities stay null until validation exists.

## Metadata version

`observation.stationVersion` compares station metadata across independently hosted services. Do not expose a database epoch ID as this value.

1. Form `capacity|systemType|latitude|longitude`.
2. Capacity is a decimal integer, or `?` when unknown.
3. System is the normalized value (`youbike_1`, `youbike_2`, `unknown`).
4. Coordinates are integer microdegrees: multiply each coordinate by 1,000,000, truncate toward zero and encode as a decimal integer, latitude first, no whitespace. This avoids cross-language floating-point decimal-rounding differences.
5. SHA-256 over UTF-8; take the first 16 bytes and encode as 32 lowercase hex characters.

Use [the shared fingerprint cases](../fixtures/station-version.json). Name/address changes do not affect the signature. Backend training epochs remain private and independent.

The observation's `time` provenance is `feed_snapshot`: provider snapshot time, not an actual rental/controller event. A 304, local fetch or unrelated metadata update must not manufacture a new observation. Missing feed time is `provider_only` and does not qualify for the current estimator.

## Errors and operation

- `400 INVALID_REQUEST`: reject malformed bodies/distributions; never silently truncate an arrival tail.
- `401 UNAUTHORIZED`: deployment requires a different API key.
- `429 RATE_LIMITED`: includes Retry-After; the Compose implementation limits source IPs to 20/minute with burst 10.
- `503 FORECAST_UNAVAILABLE`: service/database failure; do not erase inventory in the app.

`GET /health/live` and `GET /health/ready` support operations and are not required for app connection testing. The latter checks database connectivity/schema, while collector readiness separately checks feed age. Public ingress should provide TLS and rate limits appropriate to its proxy topology.
