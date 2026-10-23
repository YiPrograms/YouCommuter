package ingest

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

type RegionHealth struct {
	Region         string     `json:"region"`
	LastSnapshotAt *time.Time `json:"lastSnapshotAt"`
	LatestFeedAt   *time.Time `json:"latestFeedAt"`
	Fresh          bool       `json:"fresh"`
}
type HealthReport struct {
	Regions        []RegionHealth `json:"regions"`
	LastCheckedAt  *time.Time     `json:"lastCheckedAt"`
	LastSnapshotAt *time.Time     `json:"lastSnapshotAt"`
	NextAttemptAt  time.Time      `json:"nextAttemptAt"`
	Failures       int            `json:"consecutiveFailures"`
	LastErrorCode  string         `json:"lastErrorCode,omitempty"`
}
type HealthRepository interface {
	Ready(context.Context) error
	CollectionHealth(context.Context) (HealthReport, error)
}

func HealthHandler(repository HealthRepository) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if err := repository.Ready(ctx); err != nil {
			http.Error(w, "database unavailable", 503)
			return
		}
		report, err := repository.CollectionHealth(ctx)
		if err != nil {
			http.Error(w, "collection status unavailable", 503)
			return
		}
		status := http.StatusOK
		if len(report.Regions) == 0 {
			status = 503
		}
		for _, region := range report.Regions {
			if !region.Fresh {
				status = 503
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(report)
	})
	return mux
}
