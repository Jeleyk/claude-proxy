// Command anthropic is the Anthropic-native routing gateway: it exposes the real Anthropic
// Messages API to clients but serves every request from a Claude Code subscription account via
// the service control API (source="routing"). Rollback-safe: independent of the Claude Code
// datapath gateway.
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"claudeproxy/gateway/internal/anthropicgw"
	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/routing"
)

func main() {
	cfg := config.Load()
	port := os.Getenv("PORT")
	if port == "" {
		port = "9200"
	}
	ctrl := control.New(cfg.ServiceURL, cfg.InternalToken).WithSource("routing")

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"message":"ok"}`))
	})
	mux.Handle("/", routing.NewHandler(cfg, ctrl, anthropicgw.New()))

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("gateway-anthropic on :%s -> %s (service %s)", port, cfg.UpstreamBaseURL, cfg.ServiceURL)
	log.Fatal(srv.ListenAndServe())
}
