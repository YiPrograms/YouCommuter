package httpapi

import (
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
)

// An empty deployment key permits public predictions. No installation accounts exist.
func requireAPIKey(key string) func(http.Handler) http.Handler {
	expected := sha256.Sum256([]byte("Bearer " + key))
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			actual := sha256.Sum256([]byte(r.Header.Get("Authorization")))
			if key != "" && subtle.ConstantTimeCompare(actual[:], expected[:]) != 1 {
				writeError(w, r, http.StatusUnauthorized, "UNAUTHORIZED", "The service API key is invalid")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
