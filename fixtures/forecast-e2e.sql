-- Synthetic fixtures for a LOCAL DISPOSABLE database only. These stations and
-- historical observations are deliberately identified as e2e data.
-- Run while the local API is stopped or before opening the test route.
INSERT INTO stations (
    id, provider_id, native_id, region_code, name, address, position, capacity,
    system_type, provider_updated_at, last_seen_at, is_verified
) VALUES
    ('e2e:FORECAST_BORROW', 'e2e', 'FORECAST_BORROW', 'Taipei', '預測測試借車站', 'Synthetic E2E fixture',
     ST_SetSRID(ST_MakePoint(121.565, 25.033), 4326)::geography, 30, 'youbike_2', now() - interval '84 days', now(), true),
    ('e2e:FORECAST_RETURN', 'e2e', 'FORECAST_RETURN', 'Taipei', '預測測試還車站', 'Synthetic E2E fixture',
     ST_SetSRID(ST_MakePoint(121.575, 25.043), 4326)::geography, 24, 'youbike_2', now() - interval '84 days', now(), true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO station_epochs (station_id, started_at, position, capacity, system_type)
SELECT id, provider_updated_at, position, capacity, system_type
FROM stations s
WHERE id IN ('e2e:FORECAST_BORROW', 'e2e:FORECAST_RETURN')
  AND NOT EXISTS (SELECT 1 FROM station_epochs e WHERE e.station_id = s.id);

INSERT INTO station_current (
    station_id, station_epoch_id, observation_id, observed_at, provider_updated_at, fetched_at,
    timestamp_quality, expected_interval_seconds, rent_total, return_docks, service_status
)
SELECT s.id, e.id, 'obs_e2e_current_' || s.id, date_trunc('second', now()), now(), now(),
       'source', 60, CASE WHEN s.id = 'e2e:FORECAST_BORROW' THEN 12 ELSE 4 END,
       CASE WHEN s.id = 'e2e:FORECAST_BORROW' THEN 18 ELSE 20 END, 'operating'
FROM stations s JOIN station_epochs e ON e.station_id = s.id
WHERE s.id IN ('e2e:FORECAST_BORROW', 'e2e:FORECAST_RETURN')
ON CONFLICT (station_id) DO UPDATE SET
    observation_id = EXCLUDED.observation_id, observed_at = EXCLUDED.observed_at,
    provider_updated_at = EXCLUDED.provider_updated_at, fetched_at = EXCLUDED.fetched_at,
    rent_total = EXCLUDED.rent_total, return_docks = EXCLUDED.return_docks,
    service_status = EXCLUDED.service_status;

DELETE FROM station_snapshots WHERE station_id IN ('e2e:FORECAST_BORROW', 'e2e:FORECAST_RETURN');
INSERT INTO station_snapshots (
    observation_id, station_id, station_epoch_id, observed_at, provider_updated_at,
    fetched_at, timestamp_quality, expected_interval_seconds, rent_total, return_docks,
    service_status, revision
)
SELECT 'obs_e2e_history_' || e.station_id || '_' || d || '_' || n, e.station_id, e.id,
       t.at, t.at, t.at, 'source', 60, c.bikes, s.capacity - c.bikes, 'operating', decode('00', 'hex')
FROM station_epochs e JOIN stations s ON s.id = e.station_id
CROSS JOIN generate_series(1, 7) d
CROSS JOIN generate_series(0, 32) n
CROSS JOIN LATERAL (
    SELECT date_bin(interval '5 minutes', now(), timestamptz '2000-01-01')
           - d * interval '7 days' - interval '30 minutes' + n * interval '5 minutes' AS at
) t
CROSS JOIN LATERAL (
    SELECT CASE WHEN e.station_id = 'e2e:FORECAST_BORROW'
        THEN greatest(0, 14 + d % 3 - n / (3 + d % 4))
        ELSE s.capacity - greatest(0, 22 + d % 3 - n / (3 + d % 4)) END AS bikes
) c
WHERE e.station_id IN ('e2e:FORECAST_BORROW', 'e2e:FORECAST_RETURN');
