// Package db initializes the single current database schema.
package db

import (
	"context"
	"crypto/sha256"
	_ "embed"
	"fmt"

	"github.com/jackc/pgx/v5"
)

//go:embed schema.sql
var schema string

func Checksum() string { return fmt.Sprintf("%x", sha256.Sum256([]byte(schema))) }

// Initialize is repeatable for the current schema; other schemas must be rebuilt.
func Initialize(ctx context.Context, conn *pgx.Conn) error {
	tx, err := conn.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext('youcommuter-schema'))`); err != nil {
		return err
	}
	var initialized bool
	if err := tx.QueryRow(ctx, `SELECT to_regclass('public.app_schema') IS NOT NULL`).Scan(&initialized); err != nil {
		return err
	}
	if initialized {
		var checksum string
		if err := tx.QueryRow(ctx, `SELECT checksum FROM app_schema WHERE singleton`).Scan(&checksum); err != nil {
			return err
		}
		if checksum != Checksum() {
			return fmt.Errorf("database schema differs; recreate the database")
		}
	} else {
		if _, err := tx.Exec(ctx, schema); err != nil {
			return fmt.Errorf("initialize database schema: %w", err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO app_schema(checksum) VALUES($1)`, Checksum()); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}
