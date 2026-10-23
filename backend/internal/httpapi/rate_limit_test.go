package httpapi

import (
	"testing"
	"time"
)

func TestTokenBucketBurstAndRefill(t *testing.T) {
	now := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	limiter := newTokenBucketLimiter(60, 2)
	limiter.now = func() time.Time { return now }

	if allowed, _ := limiter.allow("installation"); !allowed {
		t.Fatal("first burst request was limited")
	}
	if allowed, _ := limiter.allow("installation"); !allowed {
		t.Fatal("second burst request was limited")
	}
	if allowed, retryAfter := limiter.allow("installation"); allowed || retryAfter != time.Second {
		t.Fatalf("empty bucket result = allowed %v, retry %v", allowed, retryAfter)
	}

	now = now.Add(time.Second)
	if allowed, _ := limiter.allow("installation"); !allowed {
		t.Fatal("bucket did not refill at 60 requests per minute")
	}
}
