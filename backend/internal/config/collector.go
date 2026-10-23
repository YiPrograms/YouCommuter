package config

import (
	"errors"
	"strconv"
	"strings"
	"time"
)

type CollectorConfig struct {
	YouBikeBaseURL           string
	DatabaseURL              string
	PollInterval, StaleAfter time.Duration
	SnapshotRetentionDays    int
}

func LoadCollector(getenv func(string) string) (CollectorConfig, error) {
	base, err := Load(func(key string) string {
		if key == "DATABASE_URL" {
			return getenv(key)
		}
		return ""
	})
	if err != nil {
		return CollectorConfig{}, err
	}
	cfg := CollectorConfig{DatabaseURL: base.DatabaseURL, SnapshotRetentionDays: 90, YouBikeBaseURL: strings.TrimSpace(getenv("YOUBIKE_BASE_URL"))}
	for _, setting := range []struct {
		key                        string
		fallback, minimum, maximum time.Duration
		target                     *time.Duration
	}{
		{"FEED_POLL_INTERVAL", time.Minute, time.Minute, time.Hour, &cfg.PollInterval},
		{"FEED_STALE_AFTER", 5 * time.Minute, time.Minute, 24 * time.Hour, &cfg.StaleAfter},
	} {
		value := strings.TrimSpace(getenv(setting.key))
		duration := setting.fallback
		if value != "" {
			duration, err = time.ParseDuration(value)
			if err != nil {
				return CollectorConfig{}, errors.New(setting.key + " must be a duration")
			}
		}
		if duration < setting.minimum || duration > setting.maximum {
			return CollectorConfig{}, errors.New(setting.key + " is outside the supported range")
		}
		*setting.target = duration
	}
	if cfg.StaleAfter < 3*cfg.PollInterval {
		return CollectorConfig{}, errors.New("FEED_STALE_AFTER must be at least three poll intervals")
	}
	if value := strings.TrimSpace(getenv("SNAPSHOT_RETENTION_DAYS")); value != "" {
		cfg.SnapshotRetentionDays, err = strconv.Atoi(value)
		if err != nil || cfg.SnapshotRetentionDays < 90 || cfg.SnapshotRetentionDays > 365 {
			return CollectorConfig{}, errors.New("SNAPSHOT_RETENTION_DAYS must be between 90 and 365")
		}
	}
	return cfg, nil
}
