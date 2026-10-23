package ingest

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"youcommuter/backend/internal/provider"
)

type rateLimitStub struct{ delay time.Duration }

func (e rateLimitStub) Error() string             { return "limited" }
func (e rateLimitStub) RetryDelay() time.Duration { return e.delay }
func TestCompletionBackoffAndRecovery(t *testing.T) {
	now := time.Now()
	for _, failures := range []int{0, 1, 4, 29} {
		c := completionFor(State{Failures: failures}, errors.New("failed"), now, time.Minute)
		if c.Failures != min(failures+1, 30) || c.NextAttemptAt.Sub(now) < time.Minute || c.NextAttemptAt.Sub(now) > 5*time.Minute {
			t.Fatalf("unbounded backoff: %+v", c)
		}
	}
	c := completionFor(State{}, rateLimitStub{20 * time.Minute}, now, time.Minute)
	if c.ErrorCode != "rate_limited" || c.NextAttemptAt.Before(now.Add(20*time.Minute)) {
		t.Fatal("ignored Retry-After")
	}
	c = completionFor(State{Failures: 10}, nil, now, time.Minute)
	if c.Failures != 0 || c.ErrorCode != "" {
		t.Fatal("success did not reset backoff")
	}
}

type sourceStub struct {
	snapshot provider.Snapshot
	err      error
}

func (s sourceStub) FetchSnapshot(context.Context, provider.Checkpoint) (provider.Snapshot, error) {
	return s.snapshot, s.err
}

type repositoryStub struct {
	state      State
	completion Completion
	reject     bool
	cancel     context.CancelFunc
	applied    bool
}

func (r *repositoryStub) CollectionState(context.Context) (State, error) { return r.state, nil }
func (r *repositoryStub) ApplySnapshot(_ context.Context, _ provider.Snapshot, c Completion) error {
	if r.reject {
		return errors.New("rejected")
	}
	r.applied = true
	r.completion = c
	r.cancel()
	return nil
}
func (r *repositoryStub) DeferCollection(_ context.Context, c Completion) error {
	r.completion = c
	r.cancel()
	return nil
}
func (*repositoryStub) PruneSnapshots(context.Context, int) (int64, error) { return 0, nil }
func TestWorkerPersistsFetchAndTransactionFailures(t *testing.T) {
	for _, tc := range []struct {
		reject bool
		err    error
		code   string
	}{{false, nil, ""}, {false, rateLimitStub{time.Hour}, "rate_limited"}, {true, nil, "snapshot_rejected"}} {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		repo := &repositoryStub{reject: tc.reject, cancel: cancel}
		worker := NewWorker(repo, sourceStub{err: tc.err}, Options{PollInterval: time.Minute, SnapshotRetentionDays: 90}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if err := worker.Run(ctx); !errors.Is(err, context.Canceled) || repo.completion.ErrorCode != tc.code {
			t.Fatalf("lost collection outcome: %v %+v", err, repo.completion)
		}
	}
}
