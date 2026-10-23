package postgres

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"youcommuter/db"
)

// This test requires an isolated disposable database, never a production URL.
func TestInitializedStore(t *testing.T) {
	url := os.Getenv("YOUCOMMUTER_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("set YOUCOMMUTER_TEST_DATABASE_URL to run database integration tests")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	conn, err := pgx.Connect(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(context.Background())
	if err := db.Initialize(ctx, conn); err != nil {
		t.Fatal(err)
	}
	if err := db.Initialize(ctx, conn); err != nil {
		t.Fatalf("initialization is not repeatable: %v", err)
	}
	store, err := Open(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.Ready(ctx); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := store.pool.QueryRow(ctx, `SELECT count(*) FROM provider_regions`).Scan(&count); err != nil || count != 0 {
		t.Fatal("areas must come from the feed", err)
	}

	t.Run("different schema requires rebuild", func(t *testing.T) {
		if _, err := conn.Exec(ctx, `UPDATE app_schema SET checksum=repeat('0',64)`); err != nil {
			t.Fatal(err)
		}
		defer func() {
			if _, err := conn.Exec(ctx, `UPDATE app_schema SET checksum=$1`, db.Checksum()); err != nil {
				t.Error(err)
			}
		}()
		if err := store.Ready(ctx); err == nil {
			t.Fatal("different schema reported ready")
		}
		if err := db.Initialize(ctx, conn); err == nil {
			t.Fatal("different schema was accepted")
		}
	})

	t.Run("nationwide snapshot", func(t *testing.T) { testSnapshotStore(t, ctx, store, url) })
	t.Run("worker recovery", func(t *testing.T) { testWorkerPipeline(t, ctx, store) })
}
