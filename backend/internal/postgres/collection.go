package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

const collectorLockKey int64 = 814205135

type CollectorLock struct{ connection *pgxpool.Conn }

func (s *Store) LockCollector(ctx context.Context) (*CollectorLock, error) {
	connection, err := s.pool.Acquire(ctx)
	if err != nil {
		return nil, err
	}
	var acquired bool
	if err := connection.QueryRow(ctx, `SELECT pg_try_advisory_lock($1)`, collectorLockKey).Scan(&acquired); err != nil || !acquired {
		connection.Release()
		return nil, errors.New("another collector is active or its lock is unavailable")
	}
	return &CollectorLock{connection: connection}, nil
}

func (l *CollectorLock) Check(ctx context.Context) error { return l.connection.Conn().Ping(ctx) }

func (l *CollectorLock) Close() {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := l.connection.Exec(ctx, `SELECT pg_advisory_unlock($1)`, collectorLockKey); err != nil {
		_ = l.connection.Hijack().Close(ctx)
		return
	}
	l.connection.Release()
}

// Until canonical day-series storage exists, raw data must cover the estimator's
// 84-day lookback. Keep at least 90 days; prune in bounded, indexed batches.
func (s *Store) PruneSnapshots(ctx context.Context, retentionDays int) (int64, error) {
	if retentionDays < 90 {
		return 0, fmt.Errorf("snapshot retention must be at least 90 days")
	}
	result, err := s.pool.Exec(ctx, `
		WITH expired AS (
		    SELECT id FROM station_snapshots
		    WHERE fetched_at < now() - $1 * interval '1 day'
		      AND COALESCE(observed_at, provider_updated_at) < now() - $1 * interval '1 day'
		    ORDER BY fetched_at, id LIMIT 10000 FOR UPDATE SKIP LOCKED
		)
		DELETE FROM station_snapshots s USING expired e WHERE s.id = e.id`, retentionDays)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}
