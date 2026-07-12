// Command gateway is the thin Go datapath for the Anthropic API (Spec B). It resolves each
// request against the Kotlin service's control API, forwards to Anthropic, relays SSE, and
// reports usage back. It never touches Postgres or Redis directly.
package main

import (
	"log"
	"net/http"
	"time"

	"claudeproxy/gateway/internal/config"
)

func main() {
	cfg := config.Load()
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"message":"ok"}`))
	})
	// The datapath handler is mounted on "/" in Task B3.

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("gateway on :%s -> %s (service %s)", cfg.Port, cfg.UpstreamBaseURL, cfg.ServiceURL)
	log.Fatal(srv.ListenAndServe())
}
