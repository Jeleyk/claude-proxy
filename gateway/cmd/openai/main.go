// Command openai is the OpenAI-compatible routing gateway: it accepts OpenAI Chat Completions
// requests and serves them from a Claude Code subscription account via the service control API
// (source="routing"), translating both request and response between the OpenAI and Anthropic
// wire formats. Point the OpenAI SDK's base_url at this gateway.
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/openaigw"
	"claudeproxy/gateway/internal/routing"
)

func main() {
	cfg := config.Load()
	port := os.Getenv("PORT")
	if port == "" {
		port = "9100"
	}
	ctrl := control.New(cfg.ServiceURL, cfg.InternalToken).WithSource("routing")

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"message":"ok"}`))
	})
	mux.Handle("/", routing.NewHandler(cfg, ctrl, openaigw.New()))

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("gateway-openai on :%s -> %s (service %s)", port, cfg.UpstreamBaseURL, cfg.ServiceURL)
	log.Fatal(srv.ListenAndServe())
}
