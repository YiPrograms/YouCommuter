package forecast

import (
	"context"
	"errors"
	"math"
	"sort"
	"strings"
	"time"

	"youcommuter/backend/internal/station"
)

const (
	canonicalStep = 5 * time.Minute
	analogWindow  = 15 * time.Minute
)

var taipei = time.FixedZone("Asia/Taipei", 8*60*60)

type Service struct {
	repository Repository
	now        func() time.Time
}

func NewService(repository Repository) *Service {
	return &Service{repository: repository, now: time.Now}
}

func (s *Service) Batch(ctx context.Context, items []RequestItem) ([]Forecast, error) {
	now := s.now().UTC()
	if err := validateRequest(items, now); err != nil {
		return nil, err
	}
	results := make([]Forecast, 0, len(items))
	for _, item := range items {
		result, err := s.calculate(ctx, item, now)
		if err != nil {
			return nil, err
		}
		results = append(results, result)
	}
	return results, nil
}

func validateRequest(items []RequestItem, now time.Time) error {
	if len(items) == 0 || len(items) > MaxBatchItems {
		return invalid("items must contain between 1 and 12 forecasts")
	}
	for _, item := range items {
		if strings.TrimSpace(item.StationID) == "" || len(item.StationID) > 200 {
			return invalid("stationId is invalid")
		}
		if !item.Metric.valid() {
			return invalid("metric is unsupported")
		}
		if item.LowCountThreshold < 0 || item.LowCountThreshold > 10 {
			return invalid("lowCountThreshold must be between 0 and 10")
		}
		if len(item.Arrival.Points) == 0 || len(item.Arrival.Points) > MaxArrivalPoints {
			return invalid("arrival points must contain between 1 and 91 values")
		}
		switch item.Arrival.Source {
		case "personal", "routing_prior", "user_estimate", "mixed":
		default:
			return invalid("arrival source is unsupported")
		}
		if item.Arrival.DistinctConfirmedRides < 0 {
			return invalid("distinctConfirmedRides cannot be negative")
		}
		probability := 0.0
		var previous time.Time
		for index, point := range item.Arrival.Points {
			if point.At.IsZero() || point.At.Before(now) {
				return invalid("arrival points cannot be in the past")
			}
			if index > 0 && !point.At.After(previous) {
				return invalid("arrival points must be sorted with unique timestamps")
			}
			if math.IsNaN(point.Probability) || math.IsInf(point.Probability, 0) || point.Probability < 0 {
				return invalid("arrival probabilities must be finite and nonnegative")
			}
			probability += point.Probability
			previous = point.At
		}
		if math.Abs(probability-1) > 1e-6 {
			return invalid("arrival probabilities must sum to one")
		}
	}
	return nil
}

func invalid(message string) error { return &InvalidRequestError{Message: message} }

func (s *Service) calculate(ctx context.Context, item RequestItem, now time.Time) (Forecast, error) {
	result := Forecast{
		StationID: item.StationID, Metric: item.Metric, Arrival: item.Arrival,
		Status: Unavailable, ReasonCodes: []string{}, LowCountThreshold: item.LowCountThreshold,
		RiskBand: Unknown, ModelVersion: ModelVersion, GeneratedAt: now, ValidUntil: now,
	}
	detail, err := s.repository.Get(ctx, item.StationID)
	if errors.Is(err, station.ErrNotFound) {
		result.ReasonCodes = []string{"UNKNOWN_STATION"}
		return result, nil
	}
	if err != nil {
		return Forecast{}, err
	}
	if detail.Observation == nil {
		result.ReasonCodes = []string{"MISSING_OBSERVATION"}
		return result, nil
	}
	observation := *detail.Observation
	observation.Freshness = station.ClassifyFreshness(
		observation.ObservedAt,
		now,
		time.Duration(observation.ExpectedIntervalSeconds)*time.Second,
	)
	result.Observation = observationResponse(detail, observation)
	result.ValidUntil = usableUntil(observation, now)

	current := item.Metric.count(observation.Counts)
	switch {
	case detail.Capacity == nil || *detail.Capacity <= 0:
		result.ReasonCodes = []string{"MISSING_CAPACITY"}
	case observation.ObservedAt == nil || observation.TimestampQuality != "feed_snapshot":
		result.ReasonCodes = []string{"MISSING_SOURCE_TIMESTAMP"}
	case observation.Freshness == station.Stale || observation.Freshness == station.Unavailable:
		result.ReasonCodes = []string{"STALE_SOURCE"}
	case observation.Status != station.Operating:
		result.ReasonCodes = []string{"NONOPERATING_STATION"}
	case current == nil || *current < 0 || *current > *detail.Capacity:
		result.ReasonCodes = []string{"MISSING_METRIC"}
	default:
		return s.estimate(ctx, result, observation, *detail.Capacity, *current)
	}
	return result, nil
}

func observationResponse(detail station.Detail, observation station.Observation) *Observation {
	return &Observation{
		StationID: detail.ID, StationVersion: station.InventoryVersion(detail.Capacity, detail.SystemType, detail.Latitude, detail.Longitude), Observation: observation,
	}
}

func usableUntil(observation station.Observation, now time.Time) time.Time {
	if observation.ObservedAt == nil || observation.ExpectedIntervalSeconds <= 0 {
		return now
	}
	window := max(10*time.Minute, 3*time.Duration(observation.ExpectedIntervalSeconds)*time.Second)
	return observation.ObservedAt.Add(window).UTC()
}

func (s *Service) estimate(
	ctx context.Context,
	result Forecast,
	observation station.Observation,
	capacity int,
	current int,
) (Forecast, error) {
	observedAt := observation.ObservedAt.UTC()
	requiredKnots := make(map[int]struct{})
	maxKnot := 0
	for _, arrival := range result.Arrival.Points {
		horizon := arrival.At.Sub(observedAt)
		if horizon < 0 || horizon > MaxHorizon {
			result.ReasonCodes = []string{"UNSUPPORTED_HORIZON"}
			return result, nil
		}
		lower, upper := surroundingKnots(horizon)
		if lower > 0 {
			requiredKnots[lower] = struct{}{}
		}
		if upper > 0 {
			requiredKnots[upper] = struct{}{}
		}
		maxKnot = max(maxKnot, upper)
	}

	issueLocal := observedAt.In(taipei)
	issueDate := localMidnight(issueLocal)
	query := HistoryQuery{
		StationID: result.StationID, StationEpoch: observation.StationEpoch,
		FirstLocalDate: issueDate.AddDate(0, 0, -HistoryDays).Format(time.DateOnly),
		LastLocalDate:  issueDate.AddDate(0, 0, -1).Format(time.DateOnly),
		StartMinute:    issueLocal.Hour()*60 + issueLocal.Minute() - 30,
		EndMinute:      issueLocal.Hour()*60 + issueLocal.Minute() + 16 + maxKnot,
		Before:         observedAt,
	}
	snapshots, err := s.repository.ForecastHistory(ctx, query)
	if err != nil {
		return Forecast{}, err
	}
	// Keep the estimator safe for replay and alternative persistence adapters too:
	// information received after the issue time must never enter historical analogs.
	eligible := make([]Snapshot, 0, len(snapshots))
	for _, snapshot := range snapshots {
		if !snapshot.ObservedAt.After(observedAt) && !snapshot.FetchedAt.After(observedAt) {
			eligible = append(eligible, snapshot)
		}
	}
	snapshots = eligible
	sort.SliceStable(snapshots, func(i, j int) bool {
		if snapshots[i].ObservedAt.Equal(snapshots[j].ObservedAt) {
			return snapshots[i].FetchedAt.Before(snapshots[j].FetchedAt)
		}
		return snapshots[i].ObservedAt.Before(snapshots[j].ObservedAt)
	})

	knots := make(map[int]knotDistribution, len(requiredKnots))
	for minutes := range requiredKnots {
		knots[minutes] = buildKnot(
			snapshots, result.Metric, current, capacity, issueLocal, minutes,
		)
	}
	result.Support = weakestSupport(knots)
	if len(requiredKnots) > 0 && (result.Support.DistinctDays < 7 || result.Support.EffectiveDays < 5) {
		result.Status = Building
		result.ReasonCodes = []string{"FORECAST_BUILDING", "INSUFFICIENT_HISTORY"}
		return result, nil
	}

	distribution, available := arrivalDistribution(result.Arrival, observedAt, current, knots)
	if !available {
		result.Status = Building
		result.ReasonCodes = []string{"FORECAST_BUILDING", "INSUFFICIENT_HISTORY"}
		return result, nil
	}
	result.Status = Experimental
	result.ReasonCodes = []string{"CALIBRATION_UNAVAILABLE", "HOLIDAY_CALENDAR_UNAVAILABLE", "FEED_SNAPSHOT_TIMESTAMPS"}
	result.MedianCount = intPointer(quantile(distribution, 0.50))
	result.P10Count = intPointer(quantile(distribution, 0.10))
	result.P90Count = intPointer(quantile(distribution, 0.90))
	// The PMF is used for quantiles, but public probabilities remain absent until
	// forward-time calibration and validation gates have passed.
	return result, nil
}
