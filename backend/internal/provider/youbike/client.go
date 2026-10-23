// Package youbike reads the official anonymous nationwide YouBike feed.
package youbike

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"youcommuter/backend/internal/provider"
	"youcommuter/backend/internal/station"
)

const BaseURL = "https://apis.youbike.com.tw/"
const maximumBodyBytes = 32 << 20

var taipei = time.FixedZone("Asia/Taipei", 8*60*60)
var errUnknownRegion = errors.New("station references a new service area")

type Config struct {
	BaseURL    string
	HTTPClient *http.Client
	Now        func() time.Time
}
type Client struct {
	base *url.URL
	http *http.Client
	now  func() time.Time
}
type RateLimitError struct{ Delay time.Duration }

func (e *RateLimitError) Error() string             { return "YouBike request rate limited" }
func (e *RateLimitError) RetryDelay() time.Duration { return e.Delay }

func New(config Config) (*Client, error) {
	if config.BaseURL == "" {
		config.BaseURL = BaseURL
	}
	base, err := url.Parse(config.BaseURL)
	if err != nil || (base.Scheme != "https" && base.Scheme != "http") || base.Host == "" || base.User != nil || base.RawQuery != "" || base.Fragment != "" {
		return nil, errors.New("invalid YouBike base URL")
	}
	if config.HTTPClient == nil {
		config.HTTPClient = &http.Client{Timeout: 30 * time.Second}
	}
	client := *config.HTTPClient
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if config.Now == nil {
		config.Now = time.Now
	}
	return &Client{base: base, http: &client, now: config.Now}, nil
}

func (c *Client) FetchSnapshot(ctx context.Context, checkpoint provider.Checkpoint) (provider.Snapshot, error) {
	refreshAreas := len(checkpoint.Regions) == 0 || checkpoint.AreasFetchedAt == nil || c.now().Sub(*checkpoint.AreasFetchedAt) >= 24*time.Hour
	if refreshAreas {
		response, err := c.get(ctx, "json/area-all.json", provider.Checkpoint{})
		if err != nil {
			return provider.Snapshot{}, err
		}
		body, err := readBody(response)
		if err != nil {
			return provider.Snapshot{}, err
		}
		checkpoint.Regions, err = decodeRegions(body)
		if err != nil {
			return provider.Snapshot{}, err
		}
		at := c.now().UTC()
		checkpoint.AreasFetchedAt = &at
	}
	requestCheckpoint := checkpoint
	if refreshAreas || !checkpoint.HasSnapshot {
		requestCheckpoint.ETag = ""
		requestCheckpoint.LastModified = ""
	}
	response, err := c.get(ctx, "json/station-yb2.json", requestCheckpoint)
	if err != nil {
		return provider.Snapshot{}, err
	}
	if response.StatusCode == http.StatusNotModified {
		response.Body.Close()
		if !requestCheckpoint.HasSnapshot || (requestCheckpoint.ETag == "" && requestCheckpoint.LastModified == "") {
			return provider.Snapshot{}, errors.New("unexpected unmaterialized snapshot response")
		}
		return provider.Snapshot{Checkpoint: checkpoint, NotModified: true}, nil
	}
	body, err := readBody(response)
	if err != nil {
		return provider.Snapshot{}, err
	}
	snapshot, err := decodeSnapshot(body, checkpoint.Regions, c.now().UTC())
	if errors.Is(err, errUnknownRegion) && !refreshAreas {
		areasResponse, fetchErr := c.get(ctx, "json/area-all.json", provider.Checkpoint{})
		if fetchErr != nil {
			return provider.Snapshot{}, fetchErr
		}
		areasBody, readErr := readBody(areasResponse)
		if readErr != nil {
			return provider.Snapshot{}, readErr
		}
		checkpoint.Regions, err = decodeRegions(areasBody)
		if err != nil {
			return provider.Snapshot{}, err
		}
		at := c.now().UTC()
		checkpoint.AreasFetchedAt = &at
		snapshot, err = decodeSnapshot(body, checkpoint.Regions, c.now().UTC())
	}
	if err != nil {
		return provider.Snapshot{}, err
	}
	snapshot.Checkpoint = checkpoint
	snapshot.ETag = response.Header.Get("ETag")
	snapshot.LastModified = response.Header.Get("Last-Modified")
	snapshot.HasSnapshot = true
	return snapshot, nil
}

func (c *Client) get(ctx context.Context, path string, checkpoint provider.Checkpoint) (*http.Response, error) {
	endpoint := c.base.ResolveReference(&url.URL{Path: path})
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return nil, errors.New("could not create feed request")
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "YouCommuter/1.0")
	// Go's transport negotiates gzip and enforces the size limit after decoding.
	if checkpoint.ETag != "" {
		request.Header.Set("If-None-Match", checkpoint.ETag)
	} else if checkpoint.LastModified != "" {
		request.Header.Set("If-Modified-Since", checkpoint.LastModified)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("fetch YouBike feed: %w", err)
	}
	if response.StatusCode == http.StatusTooManyRequests {
		response.Body.Close()
		return nil, &RateLimitError{Delay: retryAfter(response.Header.Get("Retry-After"), c.now())}
	}
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusNotModified {
		response.Body.Close()
		return nil, fmt.Errorf("YouBike returned HTTP %d", response.StatusCode)
	}
	return response, nil
}
func readBody(response *http.Response) ([]byte, error) {
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, errors.New("expected complete feed response")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maximumBodyBytes+1))
	if err != nil {
		return nil, errors.New("could not read complete YouBike feed")
	}
	if len(body) > maximumBodyBytes {
		return nil, errors.New("YouBike feed exceeds size limit")
	}
	return body, nil
}
func retryAfter(value string, now time.Time) time.Duration {
	if seconds, err := strconv.ParseInt(value, 10, 64); err == nil && seconds >= 0 && seconds <= (1<<63-1)/int64(time.Second) {
		return time.Duration(seconds) * time.Second
	}
	if at, err := http.ParseTime(value); err == nil && at.After(now) {
		return at.Sub(now)
	}
	return time.Minute
}

type areaDTO struct {
	Country string `json:"country_code"`
	Code    string `json:"area_code"`
	Name    string `json:"area_name_tw"`
}

func decodeRegions(body []byte) ([]provider.Region, error) {
	var records []areaDTO
	if err := json.Unmarshal(body, &records); err != nil || len(records) == 0 {
		return nil, errors.New("invalid YouBike area feed")
	}
	regions := make([]provider.Region, 0, len(records))
	seen := map[string]bool{}
	for _, r := range records {
		if r.Country != "00" {
			continue
		}
		if r.Code == "" || strings.TrimSpace(r.Name) == "" || seen[r.Code] {
			return nil, errors.New("invalid or duplicate YouBike area")
		}
		seen[r.Code] = true
		regions = append(regions, provider.Region{Code: r.Code, Name: r.Name})
	}
	if len(regions) == 0 {
		return nil, errors.New("no supported YouBike areas")
	}
	return regions, nil
}

type stationDTO struct {
	Country   string `json:"country_code"`
	Area      string `json:"area_code"`
	Type      int    `json:"type"`
	Number    string `json:"station_no"`
	Name      string `json:"name_tw"`
	Address   string `json:"address_tw"`
	Latitude  string `json:"lat"`
	Longitude string `json:"lng"`
	Capacity  *int   `json:"parking_spaces"`
	Status    *int   `json:"status"`
	Bikes     *int   `json:"available_spaces"`
	Docks     *int   `json:"empty_spaces"`
	Detail    *struct {
		Regular  *int `json:"yb2"`
		Electric *int `json:"eyb"`
	} `json:"available_spaces_detail"`
	Time      string `json:"time"`
	UpdatedAt string `json:"updated_at"`
}

func decodeSnapshot(body []byte, regions []provider.Region, fetchedAt time.Time) (provider.Snapshot, error) {
	var records []stationDTO
	if err := json.Unmarshal(body, &records); err != nil || len(records) == 0 {
		return provider.Snapshot{}, errors.New("invalid or empty YouBike station feed")
	}
	result := provider.Snapshot{}
	known := map[string]bool{}
	seen := map[string]bool{}
	for _, r := range regions {
		known[r.Code] = true
	}
	for _, r := range records {
		if r.Country != "00" || strings.TrimSpace(r.Number) == "" || strings.TrimSpace(r.Name) == "" || seen[r.Number] {
			return provider.Snapshot{}, errors.New("invalid station identity or area")
		}
		if !known[r.Area] {
			return provider.Snapshot{}, errUnknownRegion
		}
		seen[r.Number] = true
		lat, latErr := strconv.ParseFloat(r.Latitude, 64)
		lng, lngErr := strconv.ParseFloat(r.Longitude, 64)
		if latErr != nil || lngErr != nil || !(lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180) || (r.Capacity != nil && *r.Capacity < 0) {
			return provider.Snapshot{}, errors.New("invalid station geometry or capacity")
		}
		system := "youbike_2"
		if r.Type == 1 {
			system = "youbike_1"
		} else if r.Type != 2 {
			return provider.Snapshot{}, errors.New("unsupported station system")
		}
		updated, err := time.ParseInLocation("2006-01-02 15:04:05", r.UpdatedAt, taipei)
		if err != nil {
			return provider.Snapshot{}, errors.New("invalid feed update time")
		}
		var observed *time.Time
		if r.Time != "" {
			at, err := time.ParseInLocation("2006-01-02 15:04:05", r.Time, taipei)
			if err != nil {
				return provider.Snapshot{}, errors.New("invalid feed snapshot time")
			}
			at = at.UTC()
			observed = &at
		}
		id := "youbike:" + r.Number
		result.Stations = append(result.Stations, station.Station{ID: id, ProviderID: "youbike", NativeID: r.Number, Name: r.Name, Address: r.Address, RegionCode: r.Area, Latitude: lat, Longitude: lng, Capacity: r.Capacity, SystemType: system, ProviderUpdatedAt: updated.UTC()})
		counts := station.Counts{RentTotal: r.Bikes, ReturnDocks: r.Docks}
		if r.Detail != nil {
			counts.RentRegular = r.Detail.Regular
			counts.RentElectric = r.Detail.Electric
		}
		status := station.Unknown
		if r.Status != nil {
			switch *r.Status {
			case 1:
				status = station.Operating
			case 2:
				status = station.TemporarilyClosed
			}
		}
		result.Observations = append(result.Observations, provider.Observation{StationID: id, ObservedAt: observed, ProviderUpdatedAt: updated.UTC(), FetchedAt: fetchedAt, Counts: counts, ServiceStatus: status, ExpectedInterval: time.Minute})
	}
	return result, nil
}
