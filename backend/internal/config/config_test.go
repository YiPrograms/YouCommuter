package config

import (
	"strings"
	"testing"
	"time"
)

func TestConfiguration(t *testing.T) {
	for _, tc := range []struct {
		database, address string
		valid             bool
	}{{"postgres://localhost/youcommuter", "", true}, {"", "", false}, {"https://example.test/db", "", false}, {"postgres://localhost/", "", false}, {"postgres://localhost/db", "localhost", false}, {"postgres://user:private-secret@%/db", "", false}} {
		cfg, err := Load(func(key string) string {
			if key == "DATABASE_URL" {
				return tc.database
			}
			return tc.address
		})
		if (err == nil) != tc.valid {
			t.Fatalf("unexpected validity: %v", err)
		}
		if err != nil && strings.Contains(err.Error(), "private-secret") {
			t.Fatal("leaked secret")
		}
		if tc.valid && tc.address == "" && cfg.HTTPAddress != ":8080" {
			t.Fatal("bad default address")
		}
	}
}
func TestCollectorNeedsNoCredentials(t *testing.T) {
	cfg, err := LoadCollector(func(key string) string {
		if key == "DATABASE_URL" {
			return "postgres://localhost/youcommuter"
		}
		return ""
	})
	if err != nil || cfg.PollInterval != time.Minute || cfg.SnapshotRetentionDays != 90 {
		t.Fatalf("bad anonymous collector config: %+v %v", cfg, err)
	}
}
func TestCollectorRejectsUnsafeSchedules(t *testing.T) {
	for _, tc := range []struct{ key, value string }{{"FEED_POLL_INTERVAL", "1s"}, {"FEED_POLL_INTERVAL", "bad"}, {"FEED_STALE_AFTER", "2m"}, {"SNAPSHOT_RETENTION_DAYS", "84"}, {"SNAPSHOT_RETENTION_DAYS", "NaN"}} {
		_, err := LoadCollector(func(key string) string {
			if key == "DATABASE_URL" {
				return "postgres://localhost/db"
			}
			if key == tc.key {
				return tc.value
			}
			return ""
		})
		if err == nil {
			t.Fatalf("accepted %s=%s", tc.key, tc.value)
		}
	}
}
