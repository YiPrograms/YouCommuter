package postgres

import (
	"context"
	"errors"
	"time"

	"youcommuter/backend/internal/ingest"
	"youcommuter/backend/internal/provider"
)

func (s *Store) InitializeCollection(ctx context.Context, staleAfter time.Duration) error {
	_, err := s.pool.Exec(ctx, `UPDATE feed_collection SET stale_after_seconds=$1 WHERE singleton`, int(staleAfter/time.Second))
	return err
}
func (s *Store) CollectionState(ctx context.Context) (ingest.State, error) {
	var state ingest.State
	err := s.pool.QueryRow(ctx, `SELECT next_attempt_at,consecutive_failures,etag,last_modified,areas_fetched_at,
  last_snapshot_at IS NOT NULL AND EXISTS(SELECT 1 FROM station_current)
  FROM feed_collection WHERE singleton`).Scan(&state.NextAttemptAt, &state.Failures, &state.ETag, &state.LastModified, &state.AreasFetchedAt, &state.HasSnapshot)
	if err != nil {
		return state, err
	}
	rows, err := s.pool.Query(ctx, `SELECT code,name FROM provider_regions WHERE status<>'unavailable' ORDER BY code`)
	if err != nil {
		return state, err
	}
	defer rows.Close()
	for rows.Next() {
		var r provider.Region
		if err := rows.Scan(&r.Code, &r.Name); err != nil {
			return state, err
		}
		state.Regions = append(state.Regions, r)
	}
	return state, rows.Err()
}

// Inventory and cache validators are committed in the same transaction. A 304
// only advances the check time; it never invents a new inventory observation.
func (s *Store) ApplySnapshot(ctx context.Context, snapshot provider.Snapshot, c ingest.Completion) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if snapshot.NotModified {
		var usable bool
		if err := tx.QueryRow(ctx, `SELECT last_snapshot_at IS NOT NULL AND EXISTS(SELECT 1 FROM station_current) FROM feed_collection WHERE singleton FOR UPDATE`).Scan(&usable); err != nil {
			return err
		}
		if !usable {
			return errors.New("no materialized snapshot for validator")
		}
	} else {
		if len(snapshot.Regions) == 0 || len(snapshot.Stations) == 0 || len(snapshot.Stations) != len(snapshot.Observations) {
			return errors.New("incomplete nationwide snapshot")
		}
		known := map[string]bool{}
		for _, r := range snapshot.Regions {
			if r.Code == "" || r.Name == "" || known[r.Code] {
				return errors.New("invalid region catalog")
			}
			known[r.Code] = true
			if _, err := tx.Exec(ctx, `INSERT INTO provider_regions(code,name) VALUES($1,$2) ON CONFLICT(code) DO UPDATE SET name=EXCLUDED.name`, r.Code, r.Name); err != nil {
				return err
			}
		}
		byStation := map[string]string{}
		for _, r := range snapshot.Stations {
			if !known[r.RegionCode] || r.ProviderID != "youbike" {
				return errors.New("station outside region catalog")
			}
			byStation[r.ID] = r.RegionCode
		}
		for _, o := range snapshot.Observations {
			if _, ok := byStation[o.StationID]; !ok {
				return errors.New("observation outside station catalog")
			}
		}
		if err := applyStations(ctx, tx, snapshot.Stations); err != nil {
			return err
		}
		if err := applyAvailability(ctx, tx, snapshot.Observations); err != nil {
			return err
		}
		latest := map[string]*time.Time{}
		electric := map[string]bool{}
		counts := map[string]int{}
		for _, o := range snapshot.Observations {
			region := byStation[o.StationID]
			counts[region]++
			if o.Counts.RentElectric != nil {
				electric[region] = true
			}
			if o.ObservedAt != nil && (latest[region] == nil || o.ObservedAt.After(*latest[region])) {
				latest[region] = o.ObservedAt
			}
		}
		if _, err := tx.Exec(ctx, `UPDATE provider_regions SET status='unavailable',latest_feed_at=NULL,electric_counts=false`); err != nil {
			return err
		}
		for _, r := range snapshot.Regions {
			status := "available"
			if counts[r.Code] == 0 {
				status = "unavailable"
			}
			if _, err := tx.Exec(ctx, `UPDATE provider_regions SET status=$2,last_success_at=$3,latest_feed_at=$4,electric_counts=$5 WHERE code=$1`, r.Code, status, c.FinishedAt, latest[r.Code], electric[r.Code]); err != nil {
				return err
			}
		}
	}
	_, err = tx.Exec(ctx, `UPDATE feed_collection SET last_checked_at=$1,next_attempt_at=$2,consecutive_failures=0,last_error_code='',
 last_snapshot_at=CASE WHEN $3 THEN last_snapshot_at ELSE $1 END,etag=$4,last_modified=$5,areas_fetched_at=$6 WHERE singleton`,
		c.FinishedAt, c.NextAttemptAt, snapshot.NotModified, snapshot.ETag, snapshot.LastModified, snapshot.AreasFetchedAt)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}
func (s *Store) DeferCollection(ctx context.Context, c ingest.Completion) error {
	_, err := s.pool.Exec(ctx, `UPDATE feed_collection SET next_attempt_at=$1,consecutive_failures=$2,last_error_code=$3 WHERE singleton`, c.NextAttemptAt, c.Failures, c.ErrorCode)
	return err
}
func (s *Store) CollectionHealth(ctx context.Context) (ingest.HealthReport, error) {
	report := ingest.HealthReport{Regions: []ingest.RegionHealth{}}
	err := s.pool.QueryRow(ctx, `SELECT last_checked_at,last_snapshot_at,next_attempt_at,consecutive_failures,last_error_code FROM feed_collection WHERE singleton`).Scan(&report.LastCheckedAt, &report.LastSnapshotAt, &report.NextAttemptAt, &report.Failures, &report.LastErrorCode)
	if err != nil {
		return report, err
	}
	rows, err := s.pool.Query(ctx, `SELECT r.code,r.last_success_at,r.latest_feed_at,
 COALESCE(f.last_checked_at>=now()-f.stale_after_seconds*interval '1 second' AND r.status='available'
 AND r.latest_feed_at>=now()-f.stale_after_seconds*interval '1 second' AND r.latest_feed_at<=now()+interval '1 minute',false)
 FROM provider_regions r CROSS JOIN feed_collection f ORDER BY r.code`)
	if err != nil {
		return report, err
	}
	defer rows.Close()
	for rows.Next() {
		var r ingest.RegionHealth
		if err := rows.Scan(&r.Region, &r.LastSnapshotAt, &r.LatestFeedAt, &r.Fresh); err != nil {
			return report, err
		}
		report.Regions = append(report.Regions, r)
	}
	return report, rows.Err()
}
