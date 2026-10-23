package ingest

import (
	"context"
	"errors"
	"log/slog"
	"math/rand/v2"
	"time"

	"youcommuter/backend/internal/provider"
)

type State struct {
	provider.Checkpoint
	NextAttemptAt time.Time
	Failures      int
}
type Completion struct {
	FinishedAt, NextAttemptAt time.Time
	Failures                  int
	ErrorCode                 string
}
type Repository interface {
	CollectionState(context.Context) (State, error)
	ApplySnapshot(context.Context, provider.Snapshot, Completion) error
	DeferCollection(context.Context, Completion) error
	PruneSnapshots(context.Context, int) (int64, error)
}
type Options struct {
	PollInterval          time.Duration
	SnapshotRetentionDays int
}
type Worker struct {
	repository Repository
	provider   provider.SnapshotProvider
	options    Options
	logger     *slog.Logger
}

func NewWorker(repository Repository, source provider.SnapshotProvider, options Options, logger *slog.Logger) *Worker {
	return &Worker{repository: repository, provider: source, options: options, logger: logger}
}

// The process holds and monitors the singleton PostgreSQL lock.
func (w *Worker) Run(ctx context.Context) error {
	var maintenanceAt time.Time
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		state, err := w.repository.CollectionState(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return errors.New("could not read collection state")
		}
		now := time.Now()
		if !now.Before(state.NextAttemptAt) {
			if err := w.collect(ctx, state); err != nil {
				return err
			}
		}
		if !time.Now().Before(maintenanceAt) {
			maintenanceCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			deleted, err := w.repository.PruneSnapshots(maintenanceCtx, w.options.SnapshotRetentionDays)
			cancel()
			maintenanceAt = time.Now().Add(time.Hour)
			if err != nil {
				w.logger.Warn("snapshot retention failed")
				maintenanceAt = time.Now().Add(time.Minute)
			} else if deleted > 0 {
				w.logger.Info("snapshot retention complete", "deleted", deleted)
				if deleted == 10000 {
					maintenanceAt = time.Now().Add(10 * time.Second)
				}
			}
		}
		if !now.Before(state.NextAttemptAt) {
			continue
		}
		if err := sleep(ctx, min(time.Until(state.NextAttemptAt), 10*time.Second)); err != nil {
			return err
		}
	}
}
func (w *Worker) collect(ctx context.Context, state State) error {
	fetchCtx, cancel := context.WithTimeout(ctx, 90*time.Second)
	snapshot, fetchErr := w.provider.FetchSnapshot(fetchCtx, state.Checkpoint)
	cancel()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	completion := completionFor(state, fetchErr, time.Now().UTC(), w.options.PollInterval)
	if fetchErr == nil {
		if err := w.repository.ApplySnapshot(ctx, snapshot, completion); err == nil {
			w.logger.Info("YouBike collection complete", "not_modified", snapshot.NotModified, "stations", len(snapshot.Stations), "next_attempt_at", completion.NextAttemptAt)
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		// No new validator survives a failed transaction. Retry the prior checkpoint.
		completion = completionFor(state, errors.New("snapshot rejected"), time.Now().UTC(), w.options.PollInterval)
		completion.ErrorCode = "snapshot_rejected"
	}
	if err := w.repository.DeferCollection(ctx, completion); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return errors.New("could not persist collection retry")
	}
	w.logger.Warn("YouBike collection deferred", "reason", completion.ErrorCode, "failures", completion.Failures, "next_attempt_at", completion.NextAttemptAt)
	return nil
}
func completionFor(state State, err error, now time.Time, interval time.Duration) Completion {
	c := Completion{FinishedAt: now, NextAttemptAt: now.Add(interval)}
	if err == nil {
		return c
	}
	c.Failures = min(state.Failures+1, 30)
	c.ErrorCode = "collection_failed"
	base := time.Minute * time.Duration(1<<min(c.Failures-1, 4))
	c.NextAttemptAt = now.Add(max(interval, min(base+time.Duration(rand.Int64N(int64(base/5))), 5*time.Minute)))
	var limited interface{ RetryDelay() time.Duration }
	if errors.As(err, &limited) {
		c.ErrorCode = "rate_limited"
		until := now.Add(limited.RetryDelay())
		if until.After(c.NextAttemptAt) {
			c.NextAttemptAt = until
		}
	}
	return c
}
func sleep(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(max(duration, 0))
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
