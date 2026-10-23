// healthcheck is the shell-free Compose probe for the scratch runtime image.
package main

import (
	"io"
	"net/http"
	"os"
	"time"
)

func main() {
	if len(os.Args) != 2 {
		os.Exit(1)
	}
	client := &http.Client{Timeout: 4 * time.Second}
	response, err := client.Get(os.Args[1])
	if err != nil {
		os.Exit(1)
	}
	_, _ = io.Copy(os.Stdout, io.LimitReader(response.Body, 16<<10))
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}
