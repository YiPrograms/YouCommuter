package forecast

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"reflect"
	"testing"
	"time"

	"youcommuter/backend/internal/station"
)

type repositoryStub struct {
	detail       station.Detail
	snapshots    []Snapshot
	historyCalls int
	err          error
}

func (r *repositoryStub) Get(_ context.Context, id string) (station.Detail, error) {
	if id == "missing" {
		return station.Detail{}, station.ErrNotFound
	}
	return r.detail, r.err
}

func (r *repositoryStub) ForecastHistory(context.Context, HistoryQuery) ([]Snapshot, error) {
	r.historyCalls++
	return r.snapshots, r.err
}

func TestExperimentalForecastUsesHistoricalDeltasWithoutPublishingRisk(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	observedAt := now.Add(-time.Minute)
	repository := &repositoryStub{
		detail:    testDetail(observedAt),
		snapshots: historicalSnapshots(observedAt, 7),
	}
	service := NewService(repository)
	service.now = func() time.Time { return now }

	results, err := service.Batch(context.Background(), []RequestItem{testItem(now.Add(14 * time.Minute))})
	if err != nil {
		t.Fatal(err)
	}
	result := results[0]
	if result.Status != Experimental || result.MedianCount == nil || *result.MedianCount != 9 ||
		result.P10Count == nil || *result.P10Count != 9 || result.P90Count == nil || *result.P90Count != 9 {
		t.Fatalf("unexpected experimental estimate: %+v", result)
	}
	if result.ProbabilityEmpty != nil || result.ProbabilityAtOrBelowThreshold != nil || result.RiskBand != Unknown {
		t.Fatalf("uncalibrated risk leaked into public output: %+v", result)
	}
	if result.Support.DistinctDays != 7 || result.Support.EffectiveDays < 5 {
		t.Fatalf("unexpected support: %+v", result.Support)
	}
}

func TestSharedForecastWireFixture(t *testing.T) {
	data, err := os.ReadFile("../../../fixtures/forecast-batch.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Forecasts []Forecast `json:"forecasts"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	expected := fixture.Forecasts[0]
	service := NewService(&repositoryStub{
		detail:    testDetail(*expected.Observation.ObservedAt),
		snapshots: historicalSnapshots(*expected.Observation.ObservedAt, 7),
	})
	service.now = func() time.Time { return expected.GeneratedAt }
	results, err := service.Batch(context.Background(), []RequestItem{{
		StationID: expected.StationID, Metric: expected.Metric,
		Arrival: expected.Arrival, LowCountThreshold: expected.LowCountThreshold,
	}})
	if err != nil {
		t.Fatal(err)
	}
	actual := results[0]
	if math.Abs(actual.Support.EffectiveDays-expected.Support.EffectiveDays) > 1e-12 {
		t.Fatalf("effective support differs: %v vs %v", actual.Support, expected.Support)
	}
	actual.Support.EffectiveDays = expected.Support.EffectiveDays
	actual.Observation.StationEpoch = ""
	if !reflect.DeepEqual(actual, expected) {
		t.Fatalf("forecast wire fixture drifted:\n%+v\n%+v", actual, expected)
	}
}

func TestUnknownStationDoesNotHideHealthyBatchItems(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	service := NewService(&repositoryStub{detail: testDetail(now)})
	service.now = func() time.Time { return now }
	missing, known := testItem(now.Add(time.Minute)), testItem(now.Add(time.Minute))
	missing.StationID = "missing"
	results, err := service.Batch(context.Background(), []RequestItem{missing, known})
	if err != nil || len(results) != 2 || results[0].ReasonCodes[0] != "UNKNOWN_STATION" || results[1].Status != Building {
		t.Fatalf("unknown station broke the batch: %+v %v", results, err)
	}
}

func TestForecastBuildsUntilSevenDistinctDays(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	observedAt := now.Add(-time.Minute)
	repository := &repositoryStub{detail: testDetail(observedAt), snapshots: historicalSnapshots(observedAt, 6)}
	service := NewService(repository)
	service.now = func() time.Time { return now }

	results, err := service.Batch(context.Background(), []RequestItem{testItem(now.Add(14 * time.Minute))})
	if err != nil {
		t.Fatal(err)
	}
	result := results[0]
	if result.Status != Building || result.MedianCount != nil || result.Support.DistinctDays != 6 {
		t.Fatalf("cold start was overstated: %+v", result)
	}
}

func TestReturnForecastUsesDockChanges(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	observedAt := now.Add(-time.Minute)
	service := NewService(&repositoryStub{detail: testDetail(observedAt), snapshots: historicalSnapshots(observedAt, 7)})
	service.now = func() time.Time { return now }
	item := testItem(now.Add(14 * time.Minute))
	item.Metric = ReturnDocks
	results, err := service.Batch(context.Background(), []RequestItem{item})
	if err != nil || results[0].Status != Experimental || *results[0].MedianCount != 11 {
		t.Fatalf("dock forecast used the wrong metric: %+v %v", results, err)
	}
}

func TestForecastClipsHistoricalChangesAtPhysicalBounds(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	observedAt := now.Add(-time.Minute)
	for _, current := range []int{0, 20} {
		history := historicalSnapshots(observedAt, 7)
		for index := range history {
			count := max(0, 2-index%4)
			if current == 20 {
				count = min(20, 18+index%4)
			}
			history[index].Counts.RentTotal = &count
		}
		detail := testDetail(observedAt)
		detail.Observation.Counts.RentTotal = &current
		service := NewService(&repositoryStub{detail: detail, snapshots: history})
		service.now = func() time.Time { return now }
		results, err := service.Batch(context.Background(), []RequestItem{testItem(now.Add(14 * time.Minute))})
		if err != nil || results[0].Status != Experimental || *results[0].MedianCount != current ||
			*results[0].P10Count != current || *results[0].P90Count != current {
			t.Fatalf("forecast escaped physical bound %d: %+v %v", current, results, err)
		}
	}
}

func TestStaleSourceAndUnsupportedHorizonStayUnavailable(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 30, 0, 0, time.UTC)
	staleAt := now.Add(-20 * time.Minute)
	repository := &repositoryStub{detail: testDetail(staleAt)}
	service := NewService(repository)
	service.now = func() time.Time { return now }

	results, err := service.Batch(context.Background(), []RequestItem{testItem(now.Add(10 * time.Minute))})
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Status != Unavailable || results[0].ReasonCodes[0] != "STALE_SOURCE" || repository.historyCalls != 0 {
		t.Fatalf("stale source should not be forecast: %+v", results[0])
	}

	freshAt := now.Add(-time.Minute)
	repository.detail = testDetail(freshAt)
	results, err = service.Batch(context.Background(), []RequestItem{testItem(now.Add(90 * time.Minute))})
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Status != Unavailable || results[0].ReasonCodes[0] != "UNSUPPORTED_HORIZON" {
		t.Fatalf("unsupported horizon was not explicit: %+v", results[0])
	}
}

func TestRequestValidationRejectsMalformedArrivalDistribution(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	service := NewService(&repositoryStub{})
	service.now = func() time.Time { return now }
	item := testItem(now.Add(time.Minute))
	item.Arrival.Points[0].Probability = 0.9
	_, err := service.Batch(context.Background(), []RequestItem{item})
	var invalid *InvalidRequestError
	if !errors.As(err, &invalid) {
		t.Fatalf("expected invalid request, got %v", err)
	}
}

func TestArrivalMixtureInterpolatesPMFsAndIncludesSourceAge(t *testing.T) {
	observedAt := time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC)
	// A 7.5-minute arrival is halfway between the 5- and 10-minute changes.
	// Mixing a second arrival at 10 minutes gives 25% at 8 and 75% at 2.
	pmf, ok := arrivalDistribution(ArrivalDistribution{Points: []ArrivalPoint{
		{At: observedAt.Add(7*time.Minute + 30*time.Second), Probability: 0.5},
		{At: observedAt.Add(10 * time.Minute), Probability: 0.5},
	}}, observedAt, 10, map[int]knotDistribution{
		5: {pmf: map[int]float64{8: 1}}, 10: {pmf: map[int]float64{2: 1}},
	})
	if !ok || pmf[8] != 0.25 || pmf[2] != 0.75 || quantile(pmf, 0.5) != 2 {
		t.Fatalf("arrival mixture changed probability mass: %v", pmf)
	}
}

func TestBrokenHistoryCannotProduceSupportedForecast(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	observedAt := now.Add(-time.Minute)
	for _, defect := range []string{"gap", "closure", "late_revision", "missing_metric"} {
		t.Run(defect, func(t *testing.T) {
			history := historicalSnapshots(observedAt, 7)
			for index := 1; index < len(history); index += 4 {
				switch defect {
				case "gap":
					history[index].ObservedAt = history[index].ObservedAt.Add(time.Minute)
				case "closure":
					history[index].Status = station.TemporarilyClosed
				case "late_revision":
					history[index].FetchedAt = now.Add(time.Hour)
				case "missing_metric":
					history[index].Counts.RentTotal = nil
				}
			}
			repository := &repositoryStub{detail: testDetail(observedAt), snapshots: history}
			service := NewService(repository)
			service.now = func() time.Time { return now }
			results, err := service.Batch(context.Background(), []RequestItem{testItem(now.Add(14 * time.Minute))})
			if err != nil || results[0].Status != Building || results[0].MedianCount != nil {
				t.Fatalf("broken history produced an estimate: %+v %v", results, err)
			}
		})
	}
}

func TestHistoryCrossesMidnightWithoutWrappingWithinDay(t *testing.T) {
	now := time.Date(2026, 10, 2, 15, 56, 0, 0, time.UTC) // Friday, 23:56 Taipei.
	observedAt := now.Add(-time.Minute)
	repository := &repositoryStub{detail: testDetail(observedAt), snapshots: historicalSnapshots(observedAt, 7)}
	service := NewService(repository)
	service.now = func() time.Time { return now }
	results, err := service.Batch(context.Background(), []RequestItem{testItem(now.Add(14 * time.Minute))})
	if err != nil || results[0].Status != Experimental || *results[0].MedianCount != 9 {
		t.Fatalf("midnight forecast lost next-day endpoints: %+v %v", results, err)
	}
}

func TestZeroWeightTailStillCannotExceedHorizon(t *testing.T) {
	now := time.Date(2026, 10, 3, 0, 1, 0, 0, time.UTC)
	repository := &repositoryStub{detail: testDetail(now)}
	service := NewService(repository)
	service.now = func() time.Time { return now }
	item := testItem(now.Add(10 * time.Minute))
	item.Arrival.Points = append(item.Arrival.Points, ArrivalPoint{At: now.Add(91 * time.Minute), Probability: 0})
	results, err := service.Batch(context.Background(), []RequestItem{item})
	if err != nil || results[0].Status != Unavailable || results[0].ReasonCodes[0] != "UNSUPPORTED_HORIZON" {
		t.Fatalf("distribution tail was silently discarded: %+v %v", results, err)
	}
}

func testDetail(observedAt time.Time) station.Detail {
	capacity, bikes, docks := 20, 12, 8
	return station.Detail{
		ID: "youbike:500101001", Capacity: &capacity,
		Observation: &station.Observation{
			ID: "obs-current", ObservedAt: &observedAt,
			ProviderUpdatedAt: observedAt, FetchedAt: observedAt,
			TimestampQuality: "feed_snapshot", ExpectedIntervalSeconds: 60,
			Status:       station.Operating,
			Counts:       station.Counts{RentTotal: &bikes, ReturnDocks: &docks},
			StationEpoch: "1",
		},
	}
}

func historicalSnapshots(issue time.Time, days int) []Snapshot {
	issueLocal := issue.In(taipei)
	result := make([]Snapshot, 0, days*4)
	for index := 1; index <= days; index++ {
		date := localMidnight(issueLocal).AddDate(0, 0, -7*index)
		for step, bikes := range []int{10, 9, 8, 7} {
			observedAt := date.Add(time.Duration(issueLocal.Hour())*time.Hour + time.Duration(issueLocal.Minute()+step*5)*time.Minute)
			value := bikes
			docks := 20 - bikes
			result = append(result, Snapshot{
				ObservedAt: observedAt, FetchedAt: observedAt,
				ExpectedIntervalSeconds: 60, Status: station.Operating,
				Counts: station.Counts{RentTotal: &value, ReturnDocks: &docks},
			})
		}
	}
	return result
}

func testItem(arrival time.Time) RequestItem {
	return RequestItem{
		StationID: "youbike:500101001", Metric: RentTotal, LowCountThreshold: 3,
		Arrival: ArrivalDistribution{
			Points: []ArrivalPoint{{At: arrival, Probability: 1}},
			Source: "user_estimate", DistinctConfirmedRides: 0,
		},
	}
}
