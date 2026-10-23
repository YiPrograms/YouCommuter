package postgres

import (
	"context"
	"testing"
	"time"

	"youcommuter/backend/internal/forecast"
	"youcommuter/backend/internal/ingest"
	"youcommuter/backend/internal/provider"
	"youcommuter/backend/internal/station"
)

func ptr(v int) *int { return &v }
func sampleSnapshot(now time.Time) provider.Snapshot {
	return provider.Snapshot{Checkpoint: provider.Checkpoint{ETag: `"v1"`, HasSnapshot: true, AreasFetchedAt: &now, Regions: []provider.Region{{Code: "00", Name: "臺北市"}, {Code: "10", Name: "科學園區"}}}, Stations: []station.Station{
		{ID: "youbike:500101001", ProviderID: "youbike", NativeID: "500101001", RegionCode: "00", Name: "測試站", Address: "中正路", Latitude: 25.033, Longitude: 121.565, Capacity: ptr(30), SystemType: "youbike_2", ProviderUpdatedAt: now.AddDate(0, 0, -84)},
		{ID: "youbike:500109001", ProviderID: "youbike", NativeID: "500109001", RegionCode: "10", Name: "園區站", Latitude: 24.78, Longitude: 121.01, Capacity: ptr(20), SystemType: "youbike_2", ProviderUpdatedAt: now.AddDate(0, 0, -84)},
	}, Observations: []provider.Observation{
		{StationID: "youbike:500101001", ObservedAt: &now, ProviderUpdatedAt: now, FetchedAt: now, ExpectedInterval: time.Minute, ServiceStatus: station.Operating, Counts: station.Counts{RentTotal: ptr(5), RentRegular: ptr(3), RentElectric: ptr(2), ReturnDocks: ptr(24)}},
		{StationID: "youbike:500109001", ObservedAt: &now, ProviderUpdatedAt: now, FetchedAt: now, ExpectedInterval: time.Minute, ServiceStatus: station.Operating, Counts: station.Counts{RentTotal: ptr(0), RentRegular: ptr(0), RentElectric: ptr(0), ReturnDocks: ptr(18)}},
	}}
}
func testSnapshotStore(t *testing.T, ctx context.Context, store *Store, url string) {
	now := time.Now().UTC().Truncate(time.Second)
	snapshot := sampleSnapshot(now)
	complete := ingest.Completion{FinishedAt: now, NextAttemptAt: now.Add(time.Minute)}
	if err := store.InitializeCollection(ctx, 5*time.Minute); err != nil {
		t.Fatal(err)
	}
	lock, err := store.LockCollector(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if second, err := store.LockCollector(ctx); err == nil {
		second.Close()
		t.Fatal("duplicate collector")
	}
	lock.Close()
	if err := store.ApplySnapshot(ctx, snapshot, complete); err != nil {
		t.Fatal(err)
	}
	if err := store.ApplySnapshot(ctx, snapshot, complete); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := store.pool.QueryRow(ctx, `SELECT count(*) FROM station_snapshots`).Scan(&n); err != nil || n != 2 {
		t.Fatalf("deduplication: %d %v", n, err)
	}
	detail, err := store.Get(ctx, "youbike:500101001")
	if err != nil || *detail.Observation.Counts.RentElectric != 2 || *detail.Observation.Counts.ReturnDocks != 24 {
		t.Fatalf("stock subtype mapping: %+v %v", detail, err)
	}

	snapshot.Observations[0].ProviderUpdatedAt = now.Add(time.Second)
	if err := store.ApplySnapshot(ctx, snapshot, complete); err != nil {
		t.Fatal(err)
	}
	if err := store.pool.QueryRow(ctx, `SELECT count(*) FROM station_snapshots`).Scan(&n); err != nil || n != 2 {
		t.Fatal("provider refresh manufactured new observation", n, err)
	}
	restarted, err := Open(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	state, err := restarted.CollectionState(ctx)
	if err != nil || state.ETag != `"v1"` || !state.HasSnapshot || len(state.Regions) != 2 {
		t.Fatalf("lost checkpoint: %+v %v", state, err)
	}
	notModified := provider.Snapshot{Checkpoint: state.Checkpoint, NotModified: true}
	if err := store.ApplySnapshot(ctx, notModified, ingest.Completion{FinishedAt: now.Add(time.Second), NextAttemptAt: now.Add(time.Minute)}); err != nil {
		t.Fatal(err)
	}
	if err := store.pool.QueryRow(ctx, `SELECT count(*) FROM station_snapshots`).Scan(&n); err != nil || n != 2 {
		t.Fatal("304 created observations", n, err)
	}
	bad := sampleSnapshot(now)
	bad.ETag = `"bad"`
	bad.Regions[0].Name = "bad replacement"
	bad.Stations[0].Latitude = 200
	if err := store.ApplySnapshot(ctx, bad, complete); err == nil {
		t.Fatal("accepted corrupt snapshot")
	}
	state, _ = store.CollectionState(ctx)
	if state.ETag != `"v1"` || state.Regions[0].Name != "臺北市" {
		t.Fatal("failed transaction advanced checkpoint/catalog")
	}
	health, err := store.CollectionHealth(ctx)
	if err != nil || len(health.Regions) != 2 || !health.Regions[0].Fresh {
		t.Fatalf("freshness health: %+v %v", health, err)
	}
	if _, err := store.pool.Exec(ctx, `UPDATE provider_regions SET latest_feed_at=now()-interval '1 hour' WHERE code='00'`); err != nil {
		t.Fatal(err)
	}
	health, err = store.CollectionHealth(ctx)
	if err != nil || health.Regions[0].Fresh {
		t.Fatal("frozen feed remained available", err)
	}

	// Exercise forecast SQL and estimator with independent weekly feed snapshots.
	_, err = store.pool.Exec(ctx, `INSERT INTO station_snapshots(observation_id,station_id,station_epoch_id,observed_at,provider_updated_at,fetched_at,timestamp_quality,expected_interval_seconds,rent_total,return_docks,service_status,revision)
 SELECT 'history_'||d||'_'||n,c.station_id,c.station_epoch_id,date_bin(interval '5 minutes',$1::timestamptz,timestamptz '2000-01-01')-d*interval '7 days'+n*interval '5 minutes',$1::timestamptz-d*interval '7 days',$1::timestamptz-d*interval '7 days','feed_snapshot',60,n,10,'operating',decode('00','hex')
 FROM station_current c CROSS JOIN generate_series(1,7)d CROSS JOIN generate_series(0,3)n WHERE c.station_id='youbike:500101001'`, now)
	if err != nil {
		t.Fatal(err)
	}
	forecasts, err := forecast.NewService(store).Batch(ctx, []forecast.RequestItem{{StationID: "youbike:500101001", Metric: forecast.RentTotal, LowCountThreshold: 3, Arrival: forecast.ArrivalDistribution{Source: "user_estimate", Points: []forecast.ArrivalPoint{{At: now.Add(15 * time.Minute), Probability: 1}}}}})
	if err != nil || forecasts[0].Status != forecast.Experimental || forecasts[0].Support.DistinctDays != 7 || forecasts[0].ProbabilityEmpty != nil {
		t.Fatalf("feed forecast: %+v %v", forecasts, err)
	}
	// Capacity change creates a new epoch; older history cannot leak across it.
	snapshot = sampleSnapshot(now.Add(time.Second))
	snapshot.Stations[0].Capacity = ptr(35)
	snapshot.Stations[0].ProviderUpdatedAt = now.Add(time.Second)
	if err := store.ApplySnapshot(ctx, snapshot, complete); err != nil {
		t.Fatal(err)
	}
	if err := store.pool.QueryRow(ctx, `SELECT count(*) FROM station_epochs WHERE station_id='youbike:500101001'`).Scan(&n); err != nil || n != 2 {
		t.Fatal("capacity did not create an epoch", n, err)
	}
	// Keep at least ninety days, including late-arriving observations.
	_, err = store.pool.Exec(ctx, `INSERT INTO station_snapshots(observation_id,station_id,station_epoch_id,observed_at,provider_updated_at,fetched_at,timestamp_quality,expected_interval_seconds,rent_total,return_docks,service_status,revision)
 SELECT 'expired_'||n,c.station_id,c.station_epoch_id,now()-interval '95 days',now()-interval '95 days',now()-interval '95 days','feed_snapshot',60,1,1,'operating',decode('00','hex') FROM station_current c CROSS JOIN generate_series(1,10001)n WHERE c.station_id='youbike:500101001'`)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.PruneSnapshots(ctx, 84); err == nil {
		t.Fatal("accepted destructive retention")
	}
	for _, expected := range []int64{10000, 1, 0} {
		if deleted, err := store.PruneSnapshots(ctx, 90); err != nil || deleted != expected {
			t.Fatalf("retention %d want %d: %v", deleted, expected, err)
		}
	}
}
