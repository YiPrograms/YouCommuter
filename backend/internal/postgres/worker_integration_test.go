package postgres

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"youcommuter/backend/internal/ingest"
	"youcommuter/backend/internal/provider/youbike"
)

func testWorkerPipeline(t *testing.T, parent context.Context, store *Store) {
	areas, err := os.ReadFile("../../../fixtures/youbike/areas.json")
	if err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile("../../../fixtures/youbike/stations.json")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().In(time.FixedZone("00", 28800)).Format("2006-01-02 15:04:05")
	text := strings.ReplaceAll(strings.ReplaceAll(string(body), "2026-10-19 23:03:02", now), "2026-10-19 23:03:31", now)
	var areaCalls, feedCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "area-all") {
			areaCalls.Add(1)
			w.Write(areas)
			return
		}
		if feedCalls.Add(1) == 2 {
			w.WriteHeader(503)
			return
		}
		w.Header().Set("ETag", `"pipeline"`)
		if r.Header.Get("If-None-Match") == `"pipeline"` {
			w.WriteHeader(304)
			return
		}
		io.WriteString(w, text)
	}))
	defer server.Close()
	if _, err := store.pool.Exec(parent, `UPDATE feed_collection SET next_attempt_at=now(),areas_fetched_at=NULL,consecutive_failures=0`); err != nil {
		t.Fatal(err)
	}
	newWorker := func() *ingest.Worker {
		source, err := youbike.New(youbike.Config{BaseURL: server.URL + "/", HTTPClient: server.Client()})
		if err != nil {
			t.Fatal(err)
		}
		return ingest.NewWorker(store, source, ingest.Options{PollInterval: time.Millisecond, SnapshotRetentionDays: 90}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	}
	runUntil := func(done func(ingest.HealthReport) bool) {
		ctx, cancel := context.WithTimeout(parent, 8*time.Second)
		defer cancel()
		result := make(chan error, 1)
		go func() { result <- newWorker().Run(ctx) }()
		tick := time.NewTicker(10 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case err := <-result:
				t.Fatalf("worker ended early: %v", err)
			case <-tick.C:
				health, err := store.CollectionHealth(ctx)
				if err != nil {
					t.Fatal(err)
				}
				if done(health) {
					cancel()
					if err := <-result; !errors.Is(err, context.Canceled) {
						t.Fatal(err)
					}
					return
				}
			}
		}
	}
	runUntil(func(h ingest.HealthReport) bool { return h.Failures == 1 })
	if areaCalls.Load() != 1 || feedCalls.Load() != 2 {
		t.Fatal("duplicate nationwide/catalog requests", areaCalls.Load(), feedCalls.Load())
	}
	state, err := store.CollectionState(parent)
	if err != nil || state.ETag != `"pipeline"` || time.Until(state.NextAttemptAt) < 50*time.Second {
		t.Fatalf("lost persistent retry: %+v %v", state, err)
	}
	ctx, cancel := context.WithTimeout(parent, 40*time.Millisecond)
	defer cancel()
	if err := newWorker().Run(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
	if feedCalls.Load() != 2 {
		t.Fatal("restart bypassed retry")
	}
	var before, after int
	store.pool.QueryRow(parent, `SELECT count(*) FROM station_snapshots`).Scan(&before)
	if _, err := store.pool.Exec(parent, `UPDATE feed_collection SET next_attempt_at=now()`); err != nil {
		t.Fatal(err)
	}
	runUntil(func(h ingest.HealthReport) bool { return h.Failures == 0 })
	if areaCalls.Load() != 1 || feedCalls.Load() < 3 {
		t.Fatal("restart failed to reuse cached region catalog")
	}
	store.pool.QueryRow(parent, `SELECT count(*) FROM station_snapshots`).Scan(&after)
	if before != after {
		t.Fatal("304 manufactured history")
	}
}
