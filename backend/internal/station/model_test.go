package station

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

func TestFreshnessConformance(t *testing.T) {
	data, err := os.ReadFile("../../../fixtures/freshness.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name           string     `json:"name"`
		ObservedAt     *time.Time `json:"observedAt"`
		Now            time.Time  `json:"now"`
		CadenceSeconds int        `json:"cadenceSeconds"`
		Expected       Freshness  `json:"expected"`
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			if got := ClassifyFreshness(tc.ObservedAt, tc.Now, time.Duration(tc.CadenceSeconds)*time.Second); got != tc.Expected {
				t.Fatalf("got %s, want %s", got, tc.Expected)
			}
		})
	}
}

func TestMissingCountsRemainNull(t *testing.T) {
	zero := 0
	b, err := json.Marshal(Counts{RentTotal: &zero})
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(b, &result); err != nil {
		t.Fatal(err)
	}
	if result["rent_total"] != float64(0) || result["return_docks"] != nil {
		t.Fatalf("lost unknown/zero distinction: %s", b)
	}
}
