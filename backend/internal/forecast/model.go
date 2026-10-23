// Package forecast estimates station availability from historical changes without
// exposing uncalibrated shortage probabilities.
package forecast

import (
	"context"
	"time"

	"youcommuter/backend/internal/station"
)

const (
	MaxBatchItems    = 12
	MaxArrivalPoints = 91
	MaxHorizon       = 90 * time.Minute
	HistoryDays      = 84
	ModelVersion     = "analog-delta-feed-v1"
)

type Metric string

const (
	RentTotal    Metric = "rent_total"
	RentRegular  Metric = "rent_regular"
	RentElectric Metric = "rent_electric"
	ReturnDocks  Metric = "return_docks"
)

func (m Metric) valid() bool {
	switch m {
	case RentTotal, RentRegular, RentElectric, ReturnDocks:
		return true
	default:
		return false
	}
}

func (m Metric) count(counts station.Counts) *int {
	switch m {
	case RentTotal:
		return counts.RentTotal
	case RentRegular:
		return counts.RentRegular
	case RentElectric:
		return counts.RentElectric
	case ReturnDocks:
		return counts.ReturnDocks
	default:
		return nil
	}
}

type Status string

const (
	Building     Status = "building"
	Experimental Status = "experimental"
	Validated    Status = "validated"
	Unavailable  Status = "unavailable"
)

type RiskBand string

const (
	Healthy RiskBand = "healthy"
	Caution RiskBand = "caution"
	High    RiskBand = "high"
	Unknown RiskBand = "unknown"
)

type ArrivalPoint struct {
	At          time.Time `json:"at"`
	Probability float64   `json:"probability"`
}

type ArrivalDistribution struct {
	Points                 []ArrivalPoint `json:"points"`
	Source                 string         `json:"source"`
	DistinctConfirmedRides int            `json:"distinctConfirmedRides"`
}

type RequestItem struct {
	StationID         string              `json:"stationId"`
	Metric            Metric              `json:"metric"`
	Arrival           ArrivalDistribution `json:"arrival"`
	LowCountThreshold int                 `json:"lowCountThreshold"`
}

type Support struct {
	DistinctDays    int     `json:"distinctDays"`
	EffectiveDays   float64 `json:"effectiveDays"`
	SameWeekdayDays int     `json:"sameWeekdayDays"`
}

// Observation includes the station ID because a batch response is self-contained.
type Observation struct {
	StationID      string `json:"stationId"`
	StationVersion string `json:"stationVersion"`
	station.Observation
}

type Forecast struct {
	StationID                     string              `json:"stationId"`
	Metric                        Metric              `json:"metric"`
	Observation                   *Observation        `json:"observation"`
	Status                        Status              `json:"status"`
	ReasonCodes                   []string            `json:"reasonCodes"`
	Arrival                       ArrivalDistribution `json:"arrival"`
	MedianCount                   *int                `json:"medianCount"`
	P10Count                      *int                `json:"p10Count"`
	P90Count                      *int                `json:"p90Count"`
	ProbabilityEmpty              *float64            `json:"probabilityEmpty"`
	ProbabilityAtOrBelowThreshold *float64            `json:"probabilityAtOrBelowThreshold"`
	LowCountThreshold             int                 `json:"lowCountThreshold"`
	RiskBand                      RiskBand            `json:"riskBand"`
	Support                       Support             `json:"support"`
	ModelVersion                  string              `json:"modelVersion"`
	CalibrationVersion            *string             `json:"calibrationVersion"`
	GeneratedAt                   time.Time           `json:"generatedAt"`
	ValidUntil                    time.Time           `json:"validUntil"`
}

// Snapshot is the source observation needed to construct canonical five-minute history.
type Snapshot struct {
	ObservedAt              time.Time
	FetchedAt               time.Time
	ExpectedIntervalSeconds int
	Status                  station.ServiceStatus
	Counts                  station.Counts
}

type HistoryQuery struct {
	StationID      string
	StationEpoch   string
	FirstLocalDate string
	LastLocalDate  string
	StartMinute    int
	EndMinute      int
	Before         time.Time
}

type Repository interface {
	Get(context.Context, string) (station.Detail, error)
	ForecastHistory(context.Context, HistoryQuery) ([]Snapshot, error)
}

type InvalidRequestError struct{ Message string }

func (e *InvalidRequestError) Error() string { return e.Message }
