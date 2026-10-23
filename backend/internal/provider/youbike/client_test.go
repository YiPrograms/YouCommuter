package youbike

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"youcommuter/backend/internal/provider"
)

func fixtures(t *testing.T) ([]byte, []byte) {
	t.Helper()
	areas, e := os.ReadFile("../../../../fixtures/youbike/areas.json")
	if e != nil {
		t.Fatal(e)
	}
	stations, e := os.ReadFile("../../../../fixtures/youbike/stations.json")
	if e != nil {
		t.Fatal(e)
	}
	return areas, stations
}
func TestNationwideSnapshotAndConditionalRequests(t *testing.T) {
	areas, stations := fixtures(t)
	var areaCalls, feedCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("anonymous feed sent credentials")
		}
		if r.URL.Path == "/json/area-all.json" {
			areaCalls.Add(1)
			w.Write(areas)
			return
		}
		if r.URL.Path != "/json/station-yb2.json" {
			t.Error("unexpected endpoint", r.URL.Path)
		}
		feedCalls.Add(1)
		if r.Header.Get("If-None-Match") == `"v1"` {
			w.WriteHeader(304)
			return
		}
		w.Header().Set("ETag", `"v1"`)
		w.Write(stations)
	}))
	defer server.Close()
	now := time.Date(2026, 10, 19, 15, 4, 0, 0, time.UTC)
	client, err := New(Config{BaseURL: server.URL + "/", HTTPClient: server.Client(), Now: func() time.Time { return now }})
	if err != nil {
		t.Fatal(err)
	}
	snapshot, err := client.FetchSnapshot(context.Background(), provider.Checkpoint{})
	if err != nil || len(snapshot.Stations) != 2 || len(snapshot.Regions) != 2 || snapshot.ETag != `"v1"` {
		t.Fatalf("bad snapshot: %+v %v", snapshot, err)
	}
	first := snapshot.Observations[0]
	if *first.Counts.RentRegular != 3 || *first.Counts.RentElectric != 2 || *first.Counts.ReturnDocks != 24 || first.ObservedAt.UTC().Hour() != 15 || snapshot.Stations[0].ID != "youbike:500101001" {
		t.Fatalf("incorrect source mapping: %+v", first)
	}
	second, err := client.FetchSnapshot(context.Background(), snapshot.Checkpoint)
	if err != nil || !second.NotModified || len(second.Observations) != 0 || areaCalls.Load() != 1 || feedCalls.Load() != 2 {
		t.Fatalf("bad conditional refresh: %+v %v", second, err)
	}
	// Cached validators must not be used after the materialized snapshot is lost.
	checkpoint := snapshot.Checkpoint
	checkpoint.HasSnapshot = false
	fresh, err := client.FetchSnapshot(context.Background(), checkpoint)
	if err != nil || fresh.NotModified {
		t.Fatal("unmaterialized validator was sent", err)
	}
}
func TestMalformedOrPartialFeedIsRejected(t *testing.T) {
	areas, stations := fixtures(t)
	cases := []string{"[]", `<html>unavailable</html>`, string(stations[:len(stations)/2]), strings.Replace(string(stations), `"area_code":"10"`, `"area_code":"XX"`, 1), strings.Replace(string(stations), `"lat":"25.02605"`, `"lat":"NaN"`, 1), strings.Replace(string(stations), `"station_no":"500109001"`, `"station_no":"500101001"`, 1)}
	for _, body := range cases {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if strings.Contains(r.URL.Path, "area-all") {
				w.Write(areas)
			} else {
				fmt.Fprint(w, body)
			}
		}))
		client, _ := New(Config{BaseURL: server.URL + "/", HTTPClient: server.Client()})
		snapshot, err := client.FetchSnapshot(context.Background(), provider.Checkpoint{})
		server.Close()
		if err == nil || snapshot.HasSnapshot {
			t.Fatal("accepted incomplete feed")
		}
	}
}
func TestRateLimitAndUnexpected304(t *testing.T) {
	for _, status := range []int{429, 304, 302} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Retry-After", "120")
			w.Header().Set("Location", "/other")
			w.WriteHeader(status)
		}))
		client, _ := New(Config{BaseURL: server.URL + "/", HTTPClient: server.Client()})
		_, err := client.FetchSnapshot(context.Background(), provider.Checkpoint{})
		server.Close()
		if err == nil {
			t.Fatal("accepted invalid feed status")
		}
		if status == 429 {
			var limited *RateLimitError
			if !errors.As(err, &limited) || limited.Delay != 2*time.Minute {
				t.Fatal("lost rate-limit delay", err)
			}
		}
	}
	now := time.Now().UTC().Truncate(time.Second)
	for _, tc := range []struct {
		s     string
		delay time.Duration
	}{{"9223372036854775807", time.Minute}, {"-1", time.Minute}, {"0", 0}, {now.Add(time.Hour).Format(http.TimeFormat), time.Hour}} {
		if retryAfter(tc.s, now) != tc.delay {
			t.Fatal("unsafe Retry-After", tc.s)
		}
	}
}

func TestNewServiceAreaRefreshesCachedCatalog(t *testing.T) {
	areas, stations := fixtures(t)
	var areaCalls, feedCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "area-all") {
			areaCalls.Add(1)
			w.Write(areas)
		} else {
			feedCalls.Add(1)
			w.Write(stations)
		}
	}))
	defer server.Close()
	now := time.Now()
	client, _ := New(Config{BaseURL: server.URL + "/", HTTPClient: server.Client()})
	snapshot, err := client.FetchSnapshot(context.Background(), provider.Checkpoint{Regions: []provider.Region{{Code: "00", Name: "臺北市"}}, AreasFetchedAt: &now})
	if err != nil || len(snapshot.Regions) != 2 || areaCalls.Load() != 1 || feedCalls.Load() != 1 {
		t.Fatalf("new area could not refresh without refetching inventory: %+v %v", snapshot, err)
	}
}
func TestMissingSubtypeAndTimeRemainUnknown(t *testing.T) {
	areas, body := fixtures(t)
	regions, err := decodeRegions(areas)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.Replace(string(body), `"available_spaces_detail":{"yb2":3,"eyb":2}`, `"available_spaces_detail":null`, 1)
	text = strings.Replace(text, `"time":"2026-10-19 23:03:02"`, `"time":""`, 1)
	snapshot, err := decodeSnapshot([]byte(text), regions, time.Now())
	if err != nil || snapshot.Observations[0].ObservedAt != nil || snapshot.Observations[0].Counts.RentElectric != nil || *snapshot.Observations[0].Counts.RentTotal != 5 {
		t.Fatalf("missing source metadata fabricated counts/time: %+v %v", snapshot, err)
	}
}
