package postgres

import (
	"context"
	"errors"
	"math"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"youcommuter/backend/internal/station"
)

const stationColumns = `
	s.id, s.name, s.address, s.region_code,
	ST_Y(s.position::geometry), ST_X(s.position::geometry),
	s.capacity, s.system_type`

const observationColumns = `
	c.observation_id, c.observed_at, c.provider_updated_at, c.fetched_at,
	c.timestamp_quality, c.expected_interval_seconds, c.service_status,
	c.rent_total, c.rent_regular, c.rent_electric, c.return_docks, c.station_epoch_id`

// Metadata changes invalidate observations from the prior capacity/location epoch.
const currentObservationJoin = ` LEFT JOIN station_current c ON c.station_id = s.id
	AND c.station_epoch_id = (
		SELECT e.id FROM station_epochs e WHERE e.station_id = s.id
		ORDER BY e.started_at DESC LIMIT 1
	)`

func (s *Store) Get(ctx context.Context, id string) (station.Detail, error) {
	row := s.pool.QueryRow(ctx, `SELECT `+stationColumns+`, NULL::double precision, `+observationColumns+`
		FROM stations s `+currentObservationJoin+`
		WHERE s.id = $1 AND s.is_verified`, id)
	detail, err := scanStation(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return station.Detail{}, station.ErrNotFound
	}
	return detail, err
}

type scanner interface{ Scan(...any) error }

func scanStation(row scanner) (station.Detail, error) {
	var detail station.Detail
	var distance *float64
	var observationID, timestampQuality, serviceStatus *string
	var observedAt, providerUpdatedAt, fetchedAt *time.Time
	var expectedInterval *int
	var epochID *int64
	var counts station.Counts
	if err := row.Scan(
		&detail.ID, &detail.Name, &detail.Address, &detail.RegionCode,
		&detail.Latitude, &detail.Longitude, &detail.Capacity, &detail.SystemType, &distance,
		&observationID, &observedAt, &providerUpdatedAt, &fetchedAt, &timestampQuality,
		&expectedInterval, &serviceStatus, &counts.RentTotal, &counts.RentRegular,
		&counts.RentElectric, &counts.ReturnDocks, &epochID,
	); err != nil {
		return station.Detail{}, err
	}
	if distance != nil {
		rounded := int(math.Round(*distance))
		detail.DistanceM = &rounded
	}
	if observationID != nil {
		detail.Observation = &station.Observation{
			ID: *observationID, ObservedAt: observedAt, ProviderUpdatedAt: *providerUpdatedAt,
			FetchedAt: *fetchedAt, TimestampQuality: *timestampQuality,
			ExpectedIntervalSeconds: *expectedInterval, Status: station.ServiceStatus(*serviceStatus),
			Counts: counts, Freshness: station.ClassifyFreshness(observedAt, time.Now(), time.Duration(*expectedInterval)*time.Second),
			StationEpoch: strconv.FormatInt(*epochID, 10),
		}
	}
	return detail, nil
}
