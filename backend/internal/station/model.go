// Package station owns provider-independent station values and freshness rules.
package station

import "time"

type Freshness string

const (
	Fresh       Freshness = "fresh"
	Aging       Freshness = "aging"
	Stale       Freshness = "stale"
	Unavailable Freshness = "unavailable"
)

// ClassifyFreshness measures source age, never time since our last fetch.
func ClassifyFreshness(observedAt *time.Time, now time.Time, cadence time.Duration) Freshness {
	if observedAt == nil || cadence <= 0 || observedAt.After(now.Add(time.Minute)) {
		return Unavailable
	}
	age := max(time.Duration(0), now.Sub(*observedAt))
	if age <= max(2*time.Minute, 2*cadence+30*time.Second) {
		return Fresh
	}
	if age <= max(10*time.Minute, 3*cadence) {
		return Aging
	}
	return Stale
}

// Counts keeps missing observations distinct from measured zero inventory.
type Counts struct {
	RentTotal    *int `json:"rent_total"`
	RentRegular  *int `json:"rent_regular"`
	RentElectric *int `json:"rent_electric"`
	ReturnDocks  *int `json:"return_docks"`
}

type Station struct {
	ID                string
	ProviderID        string
	NativeID          string
	Name              string
	Address           string
	RegionCode        string
	Latitude          float64
	Longitude         float64
	Capacity          *int
	SystemType        string
	ProviderUpdatedAt time.Time
}

type ServiceStatus string

const (
	Operating         ServiceStatus = "operating"
	TemporarilyClosed ServiceStatus = "temporarily_closed"
	Retired           ServiceStatus = "retired"
	Unknown           ServiceStatus = "unknown"
)

type Observation struct {
	ID                      string        `json:"observationId"`
	ObservedAt              *time.Time    `json:"observedAt"`
	ProviderUpdatedAt       time.Time     `json:"providerUpdatedAt"`
	FetchedAt               time.Time     `json:"fetchedAt"`
	TimestampQuality        string        `json:"timestampQuality"`
	ExpectedIntervalSeconds int           `json:"expectedIntervalSeconds"`
	Status                  ServiceStatus `json:"status"`
	Counts                  Counts        `json:"metrics"`
	Freshness               Freshness     `json:"quality"`
	StationEpoch            string        `json:"-"`
}

type Detail struct {
	ID          string       `json:"id"`
	Name        string       `json:"name"`
	Address     string       `json:"address"`
	RegionCode  string       `json:"regionCode"`
	Latitude    float64      `json:"latitude"`
	Longitude   float64      `json:"longitude"`
	Capacity    *int         `json:"capacity"`
	SystemType  string       `json:"systemType"`
	DistanceM   *int         `json:"distanceMeters,omitempty"`
	Observation *Observation `json:"observation"`
}
