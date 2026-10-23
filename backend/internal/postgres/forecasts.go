package postgres

import (
	"context"
	"fmt"
	"strconv"

	"youcommuter/backend/internal/forecast"
)

func (s *Store) ForecastHistory(ctx context.Context, query forecast.HistoryQuery) ([]forecast.Snapshot, error) {
	epochID, err := strconv.ParseInt(query.StationEpoch, 10, 64)
	if err != nil || epochID <= 0 {
		return nil, fmt.Errorf("invalid station epoch")
	}
	rows, err := s.pool.Query(ctx, `
		WITH local_dates AS (
			SELECT day::date AS local_date
			FROM generate_series($3::date, $4::date, interval '1 day') AS day
		), windows AS (
			SELECT
				(local_date::timestamp + make_interval(mins => $5)) AT TIME ZONE 'Asia/Taipei' AS starts_at,
				(local_date::timestamp + make_interval(mins => $6)) AT TIME ZONE 'Asia/Taipei' AS ends_at
			FROM local_dates
		)
		SELECT ss.observed_at, ss.fetched_at, ss.expected_interval_seconds,
		       ss.service_status, ss.rent_total, ss.rent_regular,
		       ss.rent_electric, ss.return_docks
		FROM windows w
		JOIN LATERAL (
			SELECT observed_at, fetched_at, expected_interval_seconds, service_status,
			       rent_total, rent_regular, rent_electric, return_docks
			FROM station_snapshots
			WHERE station_id = $1 AND station_epoch_id = $2
			  AND timestamp_quality = 'feed_snapshot' AND observed_at IS NOT NULL
			  AND observed_at >= w.starts_at
			  AND observed_at <= LEAST(w.ends_at, $7)
			  AND fetched_at <= $7
		) ss ON true
		ORDER BY ss.observed_at, ss.fetched_at`,
		query.StationID, epochID, query.FirstLocalDate, query.LastLocalDate,
		query.StartMinute, query.EndMinute, query.Before.UTC(),
	)
	if err != nil {
		return nil, fmt.Errorf("query forecast history: %w", err)
	}
	defer rows.Close()
	result := make([]forecast.Snapshot, 0)
	for rows.Next() {
		var snapshot forecast.Snapshot
		if err := rows.Scan(
			&snapshot.ObservedAt, &snapshot.FetchedAt, &snapshot.ExpectedIntervalSeconds,
			&snapshot.Status, &snapshot.Counts.RentTotal, &snapshot.Counts.RentRegular,
			&snapshot.Counts.RentElectric, &snapshot.Counts.ReturnDocks,
		); err != nil {
			return nil, fmt.Errorf("scan forecast history: %w", err)
		}
		result = append(result, snapshot)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("read forecast history: %w", err)
	}
	return result, nil
}
