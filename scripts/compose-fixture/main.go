// Test-only anonymous YouBike HTTP fixture; never part of the runtime image.
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"
)

func main() {
	if len(os.Args) > 1 {
		method, path := "POST", "http://127.0.0.1:8082/control?mode="+os.Args[1]
		if os.Args[1] == "health" {
			method, path = "GET", "http://127.0.0.1:8082/health"
		}
		req, _ := http.NewRequest(method, path, nil)
		resp, err := (&http.Client{Timeout: 2 * time.Second}).Do(req)
		if err != nil {
			os.Exit(1)
		}
		resp.Body.Close()
		if resp.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	var mu sync.Mutex
	mode := "fresh"
	version := 1
	at := time.Now()
	requests := 0
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	http.HandleFunc("/control", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		mode = r.URL.Query().Get("mode")
		version++
		at = time.Now()
		if mode == "stale" {
			at = at.Add(-time.Hour)
		}
		w.WriteHeader(200)
	})
	http.HandleFunc("/json/area-all.json", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `[{"country_code":"00","area_code":"00","area_name_tw":"臺北市"},{"country_code":"00","area_code":"10","area_name_tw":"科學園區"}]`)
	})
	http.HandleFunc("/json/station-yb2.json", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		requests++
		etag := fmt.Sprintf(`"snapshot-%d"`, version)
		if mode == "limited" {
			w.Header().Set("Retry-After", "120")
			w.WriteHeader(429)
			return
		}
		w.Header().Set("ETag", etag)
		if mode == "invalid" {
			fmt.Fprint(w, `[{"broken":`)
			return
		}
		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(304)
			return
		}
		timestamp := at.In(time.FixedZone("Taipei", 28800)).Format("2006-01-02 15:04:05")
		data := []map[string]any{}
		for i, area := range []string{"00", "10"} {
			data = append(data, map[string]any{"country_code": "00", "area_code": area, "type": 2, "status": 1, "station_no": fmt.Sprint(500101001 + i), "name_tw": "測試站", "address_tw": "測試路", "parking_spaces": 30, "available_spaces": 5, "available_spaces_detail": map[string]int{"yb2": 3, "eyb": 2}, "empty_spaces": 24, "lat": "25.02605", "lng": "121.5436", "time": timestamp, "updated_at": timestamp})
		}
		_ = json.NewEncoder(w).Encode(data)
	})
	if err := http.ListenAndServe(":8082", nil); err != nil {
		os.Exit(1)
	}
}
