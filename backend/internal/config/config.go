// Package config validates process configuration before any services start.
package config

import (
	"errors"
	"net"
	"net/url"
	"strings"
	"time"
)

type Config struct {
	PredictionAPIKey string
	HTTPAddress      string
	DatabaseURL      string
	ShutdownTimeout  time.Duration
}

// Load accepts an environment reader so tests do not mutate process state.
func Load(getenv func(string) string) (Config, error) {
	c := Config{HTTPAddress: getenv("HTTP_ADDRESS"), DatabaseURL: getenv("DATABASE_URL"), ShutdownTimeout: 10 * time.Second, PredictionAPIKey: getenv("PREDICTION_API_KEY")}
	if c.HTTPAddress == "" {
		c.HTTPAddress = ":8080"
	}
	if _, _, err := net.SplitHostPort(c.HTTPAddress); err != nil {
		return Config{}, errors.New("HTTP_ADDRESS must be host:port")
	}
	if len(c.PredictionAPIKey) > 512 || strings.ContainsAny(c.PredictionAPIKey, "\r\n") {
		return Config{}, errors.New("PREDICTION_API_KEY is invalid")
	}
	u, err := url.Parse(c.DatabaseURL)
	if err != nil || (u.Scheme != "postgres" && u.Scheme != "postgresql") || u.Hostname() == "" || u.Path == "" || u.Path == "/" {
		return Config{}, errors.New("DATABASE_URL must identify a PostgreSQL host and database")
	}
	return c, nil
}
