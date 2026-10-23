package httpapi

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5/middleware"
	"youcommuter/backend/internal/forecast"
)

type forecastBatchRequest struct {
	Items []forecastRequestItem `json:"items"`
}

type forecastRequestItem struct {
	StationID         string                       `json:"stationId"`
	Metric            forecast.Metric              `json:"metric"`
	Arrival           forecast.ArrivalDistribution `json:"arrival"`
	LowCountThreshold *int                         `json:"lowCountThreshold"`
}

type forecastBatchResponse struct {
	Forecasts []forecast.Forecast `json:"forecasts"`
}

func forecastBatchHandler(deps Dependencies) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var request forecastBatchRequest
		if err := decodeJSONLimit(w, r, &request, 128*1024); err != nil {
			writeError(w, r, http.StatusBadRequest, "INVALID_REQUEST", "The forecast request is invalid")
			return
		}
		items := make([]forecast.RequestItem, 0, len(request.Items))
		for _, item := range request.Items {
			if item.LowCountThreshold == nil {
				writeError(w, r, http.StatusBadRequest, "INVALID_REQUEST", "lowCountThreshold is required")
				return
			}
			items = append(items, forecast.RequestItem{
				StationID: item.StationID, Metric: item.Metric, Arrival: item.Arrival,
				LowCountThreshold: *item.LowCountThreshold,
			})
		}
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		defer cancel()
		forecasts, err := deps.Forecasts.Batch(ctx, items)
		var invalid *forecast.InvalidRequestError
		switch {
		case errors.As(err, &invalid):
			writeError(w, r, http.StatusBadRequest, "INVALID_REQUEST", invalid.Error())
			return
		case err != nil:
			deps.Logger.ErrorContext(ctx, "forecast calculation failed", "request_id", middleware.GetReqID(ctx))
			writeError(w, r, http.StatusServiceUnavailable, "FORECAST_UNAVAILABLE", "Forecasts are temporarily unavailable")
			return
		}
		writeJSON(w, http.StatusOK, forecastBatchResponse{Forecasts: forecasts})
	}
}
