// collect runs the durable, nationwide YouBike worker and its private health server.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"youcommuter/backend/internal/config"
	"youcommuter/backend/internal/ingest"
	"youcommuter/backend/internal/postgres"
	"youcommuter/backend/internal/provider/youbike"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger, os.Getenv); err != nil {
		logger.Error("collector stopped", "reason", err.Error())
		os.Exit(1)
	}
}

func run(logger *slog.Logger, getenv func(string) string) error {
	cfg, err := config.LoadCollector(getenv)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	store, err := postgres.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return errors.New("could not initialize database adapter")
	}
	defer store.Close()
	startupCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := store.Ready(startupCtx); err != nil {
		return errors.New("database schema unavailable")
	}
	lock, err := store.LockCollector(startupCtx)
	if err != nil {
		return errors.New("collector lock unavailable; run only one collector")
	}
	defer lock.Close()
	if err := store.InitializeCollection(startupCtx, cfg.StaleAfter); err != nil {
		return errors.New("could not initialize collection schedule")
	}
	source, err := youbike.New(youbike.Config{BaseURL: cfg.YouBikeBaseURL})
	if err != nil {
		return err
	}
	worker := ingest.NewWorker(store, source, ingest.Options{
		PollInterval: cfg.PollInterval, SnapshotRetentionDays: cfg.SnapshotRetentionDays,
	}, logger)
	server := &http.Server{
		Addr: ":8081", Handler: ingest.HealthHandler(store),
		ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second,
	}
	workerDone, httpDone := make(chan error, 1), make(chan error, 1)
	go func() { workerDone <- worker.Run(ctx) }()
	go func() { httpDone <- server.ListenAndServe() }()
	logger.Info("collector starting", "poll_interval", cfg.PollInterval.String(), "stale_after", cfg.StaleAfter.String())
	workerStopped := false
	lockTicker := time.NewTicker(5 * time.Second)
	defer lockTicker.Stop()
running:
	for {
		select {
		case err = <-workerDone:
			workerStopped = true
			break running
		case <-httpDone:
			err = errors.New("collector health listener failed")
			break running
		case <-ctx.Done():
			break running
		case <-lockTicker.C:
			checkCtx, checkCancel := context.WithTimeout(ctx, 3*time.Second)
			checkErr := lock.Check(checkCtx)
			checkCancel()
			if checkErr != nil {
				if ctx.Err() == nil {
					err = errors.New("collector lock connection lost")
				}
				break running
			}
		}
	}
	stop()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)
	if !workerStopped {
		select {
		case <-workerDone:
		case <-shutdownCtx.Done():
			return errors.New("collector shutdown timed out")
		}
	}
	if errors.Is(err, context.Canceled) {
		return nil
	}
	return err
}
