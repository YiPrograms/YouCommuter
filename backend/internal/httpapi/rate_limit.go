package httpapi

import (
	"math"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

type tokenBucket struct {
	tokens     float64
	lastUpdate time.Time
}

type tokenBucketLimiter struct {
	mu              sync.Mutex
	buckets         map[string]tokenBucket
	tokensPerSecond float64
	capacity        float64
	now             func() time.Time
	lastSweep       time.Time
}

func newTokenBucketLimiter(requestsPerMinute, burst int) *tokenBucketLimiter {
	return &tokenBucketLimiter{
		buckets: make(map[string]tokenBucket), tokensPerSecond: float64(requestsPerMinute) / 60,
		capacity: float64(burst), now: time.Now,
	}
}

func (l *tokenBucketLimiter) allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	bucket, exists := l.buckets[key]
	if !exists {
		bucket = tokenBucket{tokens: l.capacity, lastUpdate: now}
	} else {
		elapsed := now.Sub(bucket.lastUpdate).Seconds()
		if elapsed > 0 {
			bucket.tokens = math.Min(l.capacity, bucket.tokens+elapsed*l.tokensPerSecond)
		}
		bucket.lastUpdate = now
	}
	if bucket.tokens >= 1 {
		bucket.tokens--
		l.buckets[key] = bucket
		l.sweep(now)
		return true, 0
	}
	l.buckets[key] = bucket
	l.sweep(now)
	wait := time.Duration(math.Ceil((1-bucket.tokens)/l.tokensPerSecond*1000)) * time.Millisecond
	return false, wait
}

func (l *tokenBucketLimiter) sweep(now time.Time) {
	if len(l.buckets) < 10_000 || now.Sub(l.lastSweep) < time.Minute {
		return
	}
	for key, bucket := range l.buckets {
		if now.Sub(bucket.lastUpdate) > 10*time.Minute {
			delete(l.buckets, key)
		}
	}
	l.lastSweep = now
}

func limitRequests(limiter *tokenBucketLimiter, key func(*http.Request) string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			allowed, retryAfter := limiter.allow(key(r))
			if !allowed {
				seconds := int(math.Ceil(retryAfter.Seconds()))
				if seconds < 1 {
					seconds = 1
				}
				w.Header().Set("Retry-After", strconv.Itoa(seconds))
				writeError(w, r, http.StatusTooManyRequests, "RATE_LIMITED", "Too many requests; retry later")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func requestIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}
