package postgres

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"youcommuter/backend/internal/provider"
	"youcommuter/backend/internal/station"
)

func applyStations(ctx context.Context, tx pgx.Tx, records []station.Station) error {
	rows := make([][]any, 0, len(records))
	seen := make(map[string]struct{}, len(records))
	for _, record := range records {
		if err := validateStation(record); err != nil {
			return err
		}
		if _, duplicate := seen[record.ID]; duplicate {
			return fmt.Errorf("duplicate station %q", record.ID)
		}
		seen[record.ID] = struct{}{}
		rows = append(rows, []any{
			record.ID, record.ProviderID, record.NativeID, record.Name, record.Address,
			record.RegionCode, record.Longitude, record.Latitude, record.Capacity,
			record.SystemType, record.ProviderUpdatedAt.UTC(),
		})
	}
	if _, err := tx.Exec(ctx, `CREATE TEMP TABLE ingest_stations (
		id text, provider_id text, native_id text, name text, address text, region_code text,
		longitude double precision, latitude double precision, capacity integer,
		system_type text, provider_updated_at timestamptz
	) ON COMMIT DROP`); err != nil {
		return fmt.Errorf("create station staging table: %w", err)
	}
	if _, err := tx.CopyFrom(ctx, pgx.Identifier{"ingest_stations"}, []string{
		"id", "provider_id", "native_id", "name", "address", "region_code", "longitude",
		"latitude", "capacity", "system_type", "provider_updated_at",
	}, pgx.CopyFromRows(rows)); err != nil {
		return fmt.Errorf("stage station batch: %w", err)
	}
	if _, err := tx.Exec(ctx, `CREATE TEMP TABLE changed_stations ON COMMIT DROP AS
		SELECT s.id
		FROM stations s JOIN ingest_stations i ON i.id = s.id
		WHERE i.provider_updated_at >= s.provider_updated_at AND (
			s.capacity IS DISTINCT FROM i.capacity OR s.system_type <> i.system_type OR
			NOT ST_DWithin(s.position, ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326)::geography, 50)
		)`); err != nil {
		return fmt.Errorf("detect station epoch changes: %w", err)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO stations (
			id, provider_id, native_id, region_code, name, address, position, capacity,
			system_type, provider_updated_at, last_seen_at, is_verified
		)
		SELECT id, provider_id, native_id, region_code, name, address,
		       ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
		       capacity, system_type, provider_updated_at, now(), true
		FROM ingest_stations
		ON CONFLICT (id) DO NOTHING`); err != nil {
		return fmt.Errorf("insert new stations: %w", err)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO station_epochs (station_id, started_at, position, capacity, system_type)
		SELECT s.id, i.provider_updated_at,
		       ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326)::geography,
		       i.capacity, i.system_type
		FROM ingest_stations i JOIN stations s ON s.id = i.id
		WHERE EXISTS (SELECT 1 FROM changed_stations c WHERE c.id = i.id)
		   OR NOT EXISTS (SELECT 1 FROM station_epochs e WHERE e.station_id = i.id)
		ON CONFLICT (station_id, started_at) DO UPDATE
		SET position = EXCLUDED.position, capacity = EXCLUDED.capacity, system_type = EXCLUDED.system_type`); err != nil {
		return fmt.Errorf("write station epochs: %w", err)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO stations (
			id, provider_id, native_id, region_code, name, address, position, capacity,
			system_type, provider_updated_at, last_seen_at, is_verified
		)
		SELECT id, provider_id, native_id, region_code, name, address,
		       ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
		       capacity, system_type, provider_updated_at, now(), true
		FROM ingest_stations
		ON CONFLICT (id) DO UPDATE SET
			name = EXCLUDED.name, address = EXCLUDED.address, position = EXCLUDED.position,
			capacity = EXCLUDED.capacity, system_type = EXCLUDED.system_type,
			provider_updated_at = EXCLUDED.provider_updated_at
		WHERE EXCLUDED.provider_updated_at >= stations.provider_updated_at`); err != nil {
		return fmt.Errorf("update station catalog: %w", err)
	}
	if _, err := tx.Exec(ctx, `UPDATE stations SET is_verified = false`); err != nil {
		return fmt.Errorf("mark old station catalog entries: %w", err)
	}
	if _, err := tx.Exec(ctx, `
		UPDATE stations s SET is_verified = true, last_seen_at = now()
		FROM ingest_stations i WHERE i.id = s.id`); err != nil {
		return fmt.Errorf("verify current station catalog entries: %w", err)
	}
	return nil
}

func applyAvailability(ctx context.Context, tx pgx.Tx, records []provider.Observation) error {
	metadata, err := stationMetadata(ctx, tx)
	if err != nil {
		return err
	}
	rows := make([][]any, 0, len(records))
	seen := make(map[string]struct{}, len(records))
	for _, record := range records {
		if _, duplicate := seen[record.StationID]; duplicate {
			return fmt.Errorf("duplicate availability for station %q", record.StationID)
		}
		seen[record.StationID] = struct{}{}
		meta, ok := metadata[record.StationID]
		if !ok {
			return fmt.Errorf("unknown station %q", record.StationID)
		}
		if record.ProviderUpdatedAt.IsZero() || record.FetchedAt.IsZero() || record.ExpectedInterval <= 0 {
			return fmt.Errorf("availability for station %q has invalid timestamps", record.StationID)
		}
		counts := normalizeCounts(record.Counts, meta.capacity)
		eventTime := record.ProviderUpdatedAt.UTC()
		quality := "provider_only"
		if record.ObservedAt != nil {
			eventTime = record.ObservedAt.UTC()
			quality = "feed_snapshot"
		}
		revision := observationRevision(record, counts)
		idHash := sha256.Sum256([]byte(record.StationID + "\x00" + fmt.Sprint(meta.epochID) + "\x00" + eventTime.Format(time.RFC3339Nano) + "\x00" + hex.EncodeToString(revision)))
		observationID := "obs_" + hex.EncodeToString(idHash[:16])
		rows = append(rows, []any{
			observationID, record.StationID, meta.epochID, record.ObservedAt,
			record.ProviderUpdatedAt.UTC(), record.FetchedAt.UTC(), quality,
			int(record.ExpectedInterval / time.Second), counts.RentTotal, counts.RentRegular,
			counts.RentElectric, counts.ReturnDocks, string(record.ServiceStatus), revision,
		})
	}
	if _, err := tx.Exec(ctx, `CREATE TEMP TABLE ingest_availability (
		observation_id text, station_id text, station_epoch_id bigint, observed_at timestamptz,
		provider_updated_at timestamptz, fetched_at timestamptz, timestamp_quality text,
		expected_interval_seconds integer, rent_total integer, rent_regular integer,
		rent_electric integer, return_docks integer, service_status text, revision bytea
	) ON COMMIT DROP`); err != nil {
		return fmt.Errorf("create availability staging table: %w", err)
	}
	if _, err := tx.CopyFrom(ctx, pgx.Identifier{"ingest_availability"}, []string{
		"observation_id", "station_id", "station_epoch_id", "observed_at", "provider_updated_at",
		"fetched_at", "timestamp_quality", "expected_interval_seconds", "rent_total", "rent_regular",
		"rent_electric", "return_docks", "service_status", "revision",
	}, pgx.CopyFromRows(rows)); err != nil {
		return fmt.Errorf("stage availability batch: %w", err)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO station_snapshots (
			observation_id, station_id, station_epoch_id, observed_at, provider_updated_at,
			fetched_at, timestamp_quality, expected_interval_seconds, rent_total, rent_regular,
			rent_electric, return_docks, service_status, revision
		) SELECT observation_id, station_id, station_epoch_id, observed_at, provider_updated_at,
		         fetched_at, timestamp_quality, expected_interval_seconds, rent_total, rent_regular,
		         rent_electric, return_docks, service_status, revision
		  FROM ingest_availability
		ON CONFLICT (observation_id) DO NOTHING`); err != nil {
		return fmt.Errorf("archive availability snapshots: %w", err)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO station_current (
			station_id, station_epoch_id, observation_id, observed_at, provider_updated_at,
			fetched_at, timestamp_quality, expected_interval_seconds, rent_total, rent_regular,
			rent_electric, return_docks, service_status
		) SELECT station_id, station_epoch_id, observation_id, observed_at, provider_updated_at,
		         fetched_at, timestamp_quality, expected_interval_seconds, rent_total, rent_regular,
		         rent_electric, return_docks, service_status
		  FROM ingest_availability
		ON CONFLICT (station_id) DO UPDATE SET
			station_epoch_id = EXCLUDED.station_epoch_id, observation_id = EXCLUDED.observation_id,
			observed_at = EXCLUDED.observed_at, provider_updated_at = EXCLUDED.provider_updated_at,
			fetched_at = EXCLUDED.fetched_at, timestamp_quality = EXCLUDED.timestamp_quality,
			expected_interval_seconds = EXCLUDED.expected_interval_seconds,
			rent_total = EXCLUDED.rent_total, rent_regular = EXCLUDED.rent_regular,
			rent_electric = EXCLUDED.rent_electric, return_docks = EXCLUDED.return_docks,
			service_status = EXCLUDED.service_status
		WHERE COALESCE(EXCLUDED.observed_at, EXCLUDED.provider_updated_at) >=
		      COALESCE(station_current.observed_at, station_current.provider_updated_at)`); err != nil {
		return fmt.Errorf("update current availability: %w", err)
	}
	return nil
}

type stationMeta struct {
	capacity *int
	epochID  int64
}

func stationMetadata(ctx context.Context, tx pgx.Tx) (map[string]stationMeta, error) {
	rows, err := tx.Query(ctx, `
		SELECT s.id, s.capacity, e.id
		FROM stations s
		JOIN LATERAL (
			SELECT id FROM station_epochs WHERE station_id = s.id ORDER BY started_at DESC LIMIT 1
		) e ON true
		WHERE s.is_verified`)
	if err != nil {
		return nil, fmt.Errorf("read station metadata: %w", err)
	}
	defer rows.Close()
	result := make(map[string]stationMeta)
	for rows.Next() {
		var id string
		var meta stationMeta
		if err := rows.Scan(&id, &meta.capacity, &meta.epochID); err != nil {
			return nil, fmt.Errorf("scan station metadata: %w", err)
		}
		result[id] = meta
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("read station metadata: %w", err)
	}
	return result, nil
}

func validateStation(record station.Station) error {
	if record.ID == "" || record.ProviderID == "" || record.NativeID == "" || record.Name == "" ||
		record.RegionCode == "" || record.SystemType == "" || record.ProviderUpdatedAt.IsZero() ||
		record.Latitude < -90 || record.Latitude > 90 || record.Longitude < -180 || record.Longitude > 180 ||
		(record.Capacity != nil && *record.Capacity < 0) {
		return fmt.Errorf("station %q has invalid metadata", record.ID)
	}
	return nil
}

func normalizeCounts(counts station.Counts, capacity *int) station.Counts {
	breakdownMismatch := counts.RentTotal != nil && counts.RentRegular != nil && counts.RentElectric != nil &&
		*counts.RentRegular+*counts.RentElectric != *counts.RentTotal
	result := station.Counts{
		RentTotal: validCount(counts.RentTotal, capacity), ReturnDocks: validCount(counts.ReturnDocks, capacity),
		RentRegular: validCount(counts.RentRegular, capacity), RentElectric: validCount(counts.RentElectric, capacity),
	}
	if breakdownMismatch {
		result.RentRegular, result.RentElectric = nil, nil
	}
	return result
}

func validCount(value, capacity *int) *int {
	if value == nil || *value < 0 || (capacity != nil && *value > *capacity) {
		return nil
	}
	copy := *value
	return &copy
}

func observationRevision(record provider.Observation, counts station.Counts) []byte {
	value := func(v *int) any {
		if v == nil {
			return "null"
		}
		return *v
	}
	hash := sha256.Sum256([]byte(fmt.Sprintf("%v|%v|%v|%v|%s",
		value(counts.RentTotal), value(counts.RentRegular), value(counts.RentElectric),
		value(counts.ReturnDocks), record.ServiceStatus)))
	return hash[:]
}
