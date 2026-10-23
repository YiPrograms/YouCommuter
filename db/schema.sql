CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE provider_regions (
 code text PRIMARY KEY,
 name text NOT NULL,
 status text NOT NULL DEFAULT 'not_configured' CHECK (status IN ('not_configured','available','degraded','unavailable')),
 electric_counts boolean NOT NULL DEFAULT false,
 last_success_at timestamptz,
 latest_feed_at timestamptz
);
CREATE TABLE stations (
 id text PRIMARY KEY,
 provider_id text NOT NULL CHECK (provider_id = 'youbike'),
 native_id text NOT NULL,
 region_code text NOT NULL REFERENCES provider_regions(code),
 name text NOT NULL,
 address text NOT NULL DEFAULT '',
 position geography(Point,4326) NOT NULL,
 capacity integer CHECK (capacity >= 0),
 system_type text NOT NULL,
 provider_updated_at timestamptz NOT NULL,
 last_seen_at timestamptz NOT NULL DEFAULT now(),
 is_verified boolean NOT NULL DEFAULT false,
 UNIQUE(provider_id,native_id)
);
CREATE TABLE station_epochs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 station_id text NOT NULL REFERENCES stations(id),
 started_at timestamptz NOT NULL,
 position geography(Point,4326) NOT NULL,
 capacity integer CHECK (capacity >= 0),
 system_type text NOT NULL,
 UNIQUE(station_id,started_at)
);
CREATE INDEX station_epochs_station_started_idx ON station_epochs(station_id,started_at DESC);
CREATE TABLE station_current (
 station_id text PRIMARY KEY REFERENCES stations(id),
 station_epoch_id bigint NOT NULL REFERENCES station_epochs(id),
 observation_id text NOT NULL UNIQUE,
 observed_at timestamptz,
 provider_updated_at timestamptz NOT NULL,
 fetched_at timestamptz NOT NULL,
 timestamp_quality text NOT NULL CHECK (timestamp_quality IN ('feed_snapshot','provider_only')),
 expected_interval_seconds integer NOT NULL CHECK (expected_interval_seconds > 0),
 rent_total integer CHECK (rent_total >= 0),rent_regular integer CHECK (rent_regular >= 0),
 rent_electric integer CHECK (rent_electric >= 0),return_docks integer CHECK (return_docks >= 0),
 service_status text NOT NULL CHECK (service_status IN ('operating','temporarily_closed','retired','unknown'))
);
CREATE TABLE station_snapshots (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 observation_id text NOT NULL UNIQUE,
 station_id text NOT NULL REFERENCES stations(id),
 station_epoch_id bigint NOT NULL REFERENCES station_epochs(id),
 observed_at timestamptz,
 provider_updated_at timestamptz NOT NULL,
 fetched_at timestamptz NOT NULL,
 timestamp_quality text NOT NULL CHECK (timestamp_quality IN ('feed_snapshot','provider_only')),
 expected_interval_seconds integer NOT NULL CHECK (expected_interval_seconds > 0),
 rent_total integer CHECK (rent_total >= 0),rent_regular integer CHECK (rent_regular >= 0),
 rent_electric integer CHECK (rent_electric >= 0),return_docks integer CHECK (return_docks >= 0),
 service_status text NOT NULL CHECK (service_status IN ('operating','temporarily_closed','retired','unknown')),
 revision bytea NOT NULL
);
CREATE INDEX station_snapshots_station_time_idx ON station_snapshots(station_id,COALESCE(observed_at,provider_updated_at) DESC);
CREATE INDEX station_snapshots_forecast_history_idx ON station_snapshots(station_id,station_epoch_id,observed_at) WHERE timestamp_quality='feed_snapshot' AND observed_at IS NOT NULL;
CREATE INDEX station_snapshots_retention_idx ON station_snapshots(fetched_at,id);
CREATE TABLE feed_collection (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_checked_at timestamptz,
 last_snapshot_at timestamptz,
 areas_fetched_at timestamptz,
 etag text NOT NULL DEFAULT '',last_modified text NOT NULL DEFAULT '',
 consecutive_failures integer NOT NULL DEFAULT 0 CHECK(consecutive_failures>=0),
 last_error_code text NOT NULL DEFAULT '',
 stale_after_seconds integer NOT NULL DEFAULT 300 CHECK(stale_after_seconds>0)
);
INSERT INTO feed_collection(singleton) VALUES(true);

CREATE TABLE app_schema (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 checksum text NOT NULL CHECK(length(checksum)=64)
);
