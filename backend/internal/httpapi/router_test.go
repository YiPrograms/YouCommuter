package httpapi

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
	"youcommuter/backend/internal/forecast"
	"youcommuter/backend/internal/station"
)

type stationRepositoryStub struct {
	detail station.Detail
	err    error
}

type forecastRepositoryStub struct{ stationRepositoryStub }

func (forecastRepositoryStub) ForecastHistory(context.Context, forecast.HistoryQuery) ([]forecast.Snapshot, error) {
	return []forecast.Snapshot{}, nil
}

func (s stationRepositoryStub) Get(context.Context, string) (station.Detail, error) {
	return s.detail, s.err
}
func TestForecastRouteContract(t *testing.T) {
	now := time.Now().UTC()
	capacity, bikes, docks := 20, 12, 8
	repository := forecastRepositoryStub{stationRepositoryStub: stationRepositoryStub{detail: station.Detail{
		ID: "youbike:500101001", Capacity: &capacity,
		Observation: &station.Observation{
			ID: "obs-current", ObservedAt: &now, ProviderUpdatedAt: now, FetchedAt: now,
			TimestampQuality: "feed_snapshot", ExpectedIntervalSeconds: 60, Status: station.Operating,
			Counts: station.Counts{RentTotal: &bikes, ReturnDocks: &docks}, StationEpoch: "1",
		},
	}}}
	router := NewRouter(Dependencies{
		Forecasts: forecast.NewService(repository),
		Ready:     func(context.Context) error { return nil },
		Logger:    slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	body := `{"items":[{"stationId":"youbike:500101001","metric":"rent_total","arrival":{"points":[{"at":"` +
		now.Add(15*time.Minute).Format(time.RFC3339Nano) + `","probability":1}],"source":"user_estimate","distinctConfirmedRides":0},"lowCountThreshold":3}]}`
	response := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/v1/forecasts:batch", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"status":"building"`) ||
		!strings.Contains(response.Body.String(), `"probabilityEmpty":null`) {
		t.Fatalf("unexpected forecast response: %d %s", response.Code, response.Body.String())
	}

	invalidResponse := httptest.NewRecorder()
	invalid := httptest.NewRequest(http.MethodPost, "/v1/forecasts:batch", strings.NewReader(`{"items":[]}`))
	router.ServeHTTP(invalidResponse, invalid)
	if invalidResponse.Code != http.StatusBadRequest || !strings.Contains(invalidResponse.Body.String(), `"code":"INVALID_REQUEST"`) {
		t.Fatalf("invalid forecast request was accepted: %d %s", invalidResponse.Code, invalidResponse.Body.String())
	}
	for _, threshold := range []string{"", `,"lowCountThreshold":null`} {
		missingThreshold := strings.Replace(body, `,"lowCountThreshold":3`, threshold, 1)
		request := httptest.NewRequest(http.MethodPost, "/v1/forecasts:batch", strings.NewReader(missingThreshold))
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("missing threshold became zero: %d %s", response.Code, response.Body.String())
		}
	}
}

func TestPredictionOnlyRoutes(t *testing.T) {
	for _, key := range []string{"", "private-key"} {
		router := NewRouter(Dependencies{Forecasts: forecast.NewService(forecastRepositoryStub{}), APIKey: key, Ready: func(context.Context) error { return nil }, Logger: slog.Default()})
		for _, path := range []string{"/v1/coverage", "/v1/stations", "/v1/installations"} {
			w := httptest.NewRecorder()
			router.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
			if w.Code != 404 {
				t.Fatal(path, w.Code)
			}
		}
		w := httptest.NewRecorder()
		router.ServeHTTP(w, httptest.NewRequest("GET", "/health/ready", nil))
		if w.Code != 200 {
			t.Fatal(w.Code)
		}
		w = httptest.NewRecorder()
		router.ServeHTTP(w, httptest.NewRequest("POST", "/v1/forecasts:batch", strings.NewReader(`{"items":[]}`)))
		expected := 400
		if key != "" {
			expected = 401
		}
		if w.Code != expected {
			t.Fatal(key, w.Code)
		}
		if key != "" {
			r := httptest.NewRequest("POST", "/v1/forecasts:batch", strings.NewReader(`{"items":[]}`))
			r.Header.Set("Authorization", "Bearer "+key)
			w = httptest.NewRecorder()
			router.ServeHTTP(w, r)
			if w.Code != 400 {
				t.Fatal(w.Code)
			}
		}
	}
}
