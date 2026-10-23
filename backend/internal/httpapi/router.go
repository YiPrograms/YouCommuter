// Package httpapi adapts application services to HTTP, without database knowledge.
package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"youcommuter/backend/internal/forecast"
)

type Dependencies struct {
	Forecasts *forecast.Service
	APIKey    string
	Ready     func(context.Context) error
	Logger    *slog.Logger
}

func NewRouter(deps Dependencies) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID, middleware.Recoverer)
	forecastLimiter := newTokenBucketLimiter(20, 10)
	r.Get("/health/live", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
	})
	r.Get("/health/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := deps.Ready(ctx); err != nil {
			writeError(w, r, http.StatusServiceUnavailable, "NOT_READY", "Service dependencies are not ready")
			return
		}
		writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
	})
	r.With(requireAPIKey(deps.APIKey), limitRequests(forecastLimiter, requestIP)).Post("/v1/forecasts:batch", forecastBatchHandler(deps))

	r.NotFound(func(w http.ResponseWriter, r *http.Request) {
		writeError(w, r, http.StatusNotFound, "NOT_FOUND", "Endpoint not found")
	})
	r.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) {
		writeError(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "Method not allowed")
	})
	return r
}

type statusResponse struct {
	Status string `json:"status"`
}
type errorResponse struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	RequestID string `json:"requestId"`
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	writeJSON(w, status, errorResponse{Code: code, Message: message, RequestID: middleware.GetReqID(r.Context())})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
