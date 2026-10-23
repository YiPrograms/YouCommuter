package station

import (
	"encoding/json"
	"os"
	"testing"
)

func TestPortableInventoryVersion(t *testing.T) {
	body, err := os.ReadFile("../../../fixtures/station-version.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Capacity            *int
		SystemType          string
		Latitude, Longitude float64
		Version             string
	}
	if err := json.Unmarshal(body, &cases); err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		if got := InventoryVersion(c.Capacity, c.SystemType, c.Latitude, c.Longitude); got != c.Version {
			t.Fatal(got, c.Version)
		}
	}
}
