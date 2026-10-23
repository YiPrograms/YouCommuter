// initdb initializes the current schema. Schema changes require a fresh database.
package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/jackc/pgx/v5"
	"youcommuter/backend/internal/config"
	"youcommuter/db"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	conn, err := pgx.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("connect to schema database: %w", err)
	}
	defer conn.Close(context.Background())
	if err := db.Initialize(ctx, conn); err != nil {
		return err
	}
	fmt.Println("Database schema initialized")
	return nil
}
