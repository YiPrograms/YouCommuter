// Package provider defines complete, provider-supplied inventory snapshots.
package provider

import (
	"context"
	"time"
	"youcommuter/backend/internal/station"
)

type Region struct{ Code, Name string }

type Observation struct {
	StationID                    string
	ObservedAt                   *time.Time
	ProviderUpdatedAt, FetchedAt time.Time
	Counts                       station.Counts
	ServiceStatus                station.ServiceStatus
	ExpectedInterval             time.Duration
}

// Checkpoint is only committed together with the snapshot it validates.
type Checkpoint struct {
	ETag, LastModified string
	HasSnapshot        bool
	Regions            []Region
	AreasFetchedAt     *time.Time
}

type Snapshot struct {
	Checkpoint
	Stations     []station.Station
	Observations []Observation
	NotModified  bool
}

type SnapshotProvider interface {
	FetchSnapshot(context.Context, Checkpoint) (Snapshot, error)
}
