package station

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
)

// InventoryVersion lets independent clients compare metadata without sharing database epoch IDs.
func InventoryVersion(capacity *int, system string, latitude, longitude float64) string {
	c := "?"
	if capacity != nil {
		c = fmt.Sprint(*capacity)
	}
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s|%s|%d|%d", c, system, int64(latitude*1_000_000), int64(longitude*1_000_000))))
	return hex.EncodeToString(sum[:16])
}
