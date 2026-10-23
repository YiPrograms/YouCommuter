package ingest

import (
	"context"
	"errors"
	"net/http/httptest"
	"strings"
	"testing"
)

type healthStub struct {
	report HealthReport
	err    error
}

func (s healthStub) Ready(context.Context) error                            { return s.err }
func (s healthStub) CollectionHealth(context.Context) (HealthReport, error) { return s.report, nil }
func TestHealthRequiresFreshRegionsAndSanitizesErrors(t *testing.T) {
	for _, tc := range []struct {
		repo   healthStub
		status int
	}{{healthStub{}, 503}, {healthStub{report: HealthReport{Regions: []RegionHealth{{Region: "00"}}}}, 503}, {healthStub{report: HealthReport{Regions: []RegionHealth{{Region: "00", Fresh: true}}}}, 200}, {healthStub{err: errors.New("private database secret")}, 503}} {
		response := httptest.NewRecorder()
		HealthHandler(tc.repo).ServeHTTP(response, httptest.NewRequest("GET", "/health/ready", nil))
		if response.Code != tc.status || strings.Contains(response.Body.String(), "private") {
			t.Fatalf("bad health: %d %s", response.Code, response.Body.String())
		}
	}
}
