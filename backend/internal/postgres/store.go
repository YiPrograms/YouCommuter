// Package postgres implements persistence adapters; domain packages do not import it.
package postgres

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
	"youcommuter/db"
)

type Store struct{ pool *pgxpool.Pool }

func Open(ctx context.Context, databaseURL string) (*Store, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse database configuration: %w", err)
	}
	cfg.MaxConns = 20
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("create database pool: %w", err)
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close() { s.pool.Close() }

func (s *Store) Ready(ctx context.Context) error {
	if err := s.pool.Ping(ctx); err != nil {
		return fmt.Errorf("database unavailable: %w", err)
	}
	var checksum string
	if err := s.pool.QueryRow(ctx, `SELECT checksum FROM app_schema WHERE singleton`).Scan(&checksum); err != nil {
		return fmt.Errorf("read schema checksum: %w", err)
	}
	if checksum != db.Checksum() {
		return fmt.Errorf("database schema differs; recreate the database")
	}

	return nil
}
