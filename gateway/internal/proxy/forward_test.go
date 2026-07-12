package proxy

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

// testHandler builds a Handler pointed at the given upstream base URL.
func testHandler(upstreamBase string) *Handler {
	cfg := &config.Config{UpstreamBaseURL: upstreamBase}
	return NewHandler(cfg, control.New("http://unused", "tok"))
}

func TestForwardNonSSECopiesStatusBodyAndParsesUsage(t *testing.T) {
	var gotHeaders http.Header
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHeaders = r.Header.Clone()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("anthropic-ratelimit-unified-5h-utilization", "0.42")
		w.WriteHeader(200)
		_, _ = io.WriteString(w, `{"model":"claude-opus-4-8","usage":{"input_tokens":1200,"output_tokens":340,"cache_read_input_tokens":10,"cache_creation_input_tokens":512}}`)
	}))
	defer upstream.Close()

	h := testHandler(upstream.URL)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{"model":"claude-opus-4-8"}`))
	req.Header.Set("x-client-id", "leaky")
	req.Header.Set("x-stainless-lang", "go")
	req.Header.Set("Authorization", "Bearer cxp_client")
	rec := httptest.NewRecorder()

	cand := control.Candidate{AccountID: 7, Type: "OAUTH", AuthHeaders: map[string]string{"Authorization": "Bearer sk-oat", "anthropic-beta": "oauth-2025-04-20"}}
	res := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, []byte(`{"model":"claude-opus-4-8"}`), false)

	if res.retry {
		t.Fatal("should not retry on 200")
	}
	if rec.Code != 200 {
		t.Errorf("status = %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "claude-opus-4-8") {
		t.Errorf("body = %s", rec.Body.String())
	}
	// Auth swapped to the account's; telemetry + client-id stripped.
	if gotHeaders.Get("Authorization") != "Bearer sk-oat" {
		t.Errorf("upstream Authorization = %q", gotHeaders.Get("Authorization"))
	}
	if gotHeaders.Get("x-client-id") != "" {
		t.Errorf("x-client-id leaked: %q", gotHeaders.Get("x-client-id"))
	}
	if gotHeaders.Get("x-stainless-lang") != "" {
		t.Errorf("x-stainless leaked: %q", gotHeaders.Get("x-stainless-lang"))
	}
	if gotHeaders.Get("anthropic-version") != "2023-06-01" {
		t.Errorf("anthropic-version default missing: %q", gotHeaders.Get("anthropic-version"))
	}
	// Usage parsed into the report.
	if res.report.Input != 1200 || res.report.Output != 340 || res.report.CacheRead != 10 || res.report.CacheWrite != 512 {
		t.Errorf("usage report = %+v", res.report)
	}
	if res.report.RatelimitHeaders["anthropic-ratelimit-unified-5h-utilization"] != "0.42" {
		t.Errorf("ratelimit header not forwarded: %+v", res.report.RatelimitHeaders)
	}
}

func TestForwardRetryableStatusRetriesWhenAllowed(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(529)
		_, _ = io.WriteString(w, `{"error":"overloaded"}`)
	}))
	defer upstream.Close()

	h := testHandler(upstream.URL)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	cand := control.Candidate{AccountID: 1, Type: "OAUTH"}
	res := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand, cand}, 0, nil, []byte(`{}`), true)

	if !res.retry {
		t.Fatal("529 with canRetry should retry")
	}
	if res.report.Status != 529 {
		t.Errorf("report status = %d", res.report.Status)
	}
}

func TestForwardRetryableStatusPassesThroughOnLastAttempt(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(529)
		_, _ = io.WriteString(w, `{"error":"overloaded"}`)
	}))
	defer upstream.Close()

	h := testHandler(upstream.URL)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	cand := control.Candidate{AccountID: 1, Type: "OAUTH"}
	res := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, []byte(`{}`), false)

	if res.retry {
		t.Fatal("last attempt must pass through, not retry")
	}
	if rec.Code != 529 {
		t.Errorf("client status = %d, want 529", rec.Code)
	}
}
