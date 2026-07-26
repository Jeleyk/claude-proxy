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
	res := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(`{"model":"claude-opus-4-8"}`), false, false)

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

func TestForwardMergesClientAnthropicBetaAndPreservesBody(t *testing.T) {
	var gotBeta string
	var gotBody string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotBeta = r.Header.Get("anthropic-beta")
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = io.WriteString(w, `{"model":"m","usage":{"input_tokens":1}}`)
	}))
	defer upstream.Close()

	h := testHandler(upstream.URL)
	// Client sends context-management beta + a context_management body field (like Claude Code).
	body := `{"model":"claude-opus-4-8","context_management":{"edits":[]}}`
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(body))
	req.Header.Set("anthropic-beta", "context-management-2025-06-27,fine-grained-tool-streaming-2025-05-14")
	rec := httptest.NewRecorder()

	// Account provides the oauth beta — it must be APPENDED, not replace the client's.
	cand := control.Candidate{AccountID: 1, Type: "OAUTH", AuthHeaders: map[string]string{
		"Authorization": "Bearer sk-oat", "anthropic-beta": "oauth-2025-04-20",
	}}
	h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(body), false, false)

	// Client betas preserved AND the account's oauth beta appended.
	for _, want := range []string{"context-management-2025-06-27", "fine-grained-tool-streaming-2025-05-14", "oauth-2025-04-20"} {
		if !strings.Contains(gotBeta, want) {
			t.Errorf("upstream anthropic-beta %q missing %q", gotBeta, want)
		}
	}
	// The client's context_management body field must reach the upstream unchanged.
	if !strings.Contains(gotBody, `"context_management"`) {
		t.Errorf("context_management body field lost: %s", gotBody)
	}
}

func TestMergeBeta(t *testing.T) {
	if got := mergeBeta(nil, "oauth-2025-04-20"); got != "oauth-2025-04-20" {
		t.Errorf("empty client -> %q", got)
	}
	if got := mergeBeta([]string{"oauth-2025-04-20"}, "oauth-2025-04-20"); got != "oauth-2025-04-20" {
		t.Errorf("already present should dedupe -> %q", got)
	}
	if got := mergeBeta([]string{"ctx-2025,fgts-2025"}, "oauth-2025-04-20"); got != "ctx-2025,fgts-2025,oauth-2025-04-20" {
		t.Errorf("merge -> %q", got)
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
	res := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand, cand}, 0, nil, nil, []byte(`{}`), true, false)

	if !res.retry {
		t.Fatal("529 with canRetry should retry")
	}
	if res.report.Status != 529 {
		t.Errorf("report status = %d", res.report.Status)
	}
}

// 403 is account-scoped upstream (suspended / past-due subscription): the pool can still serve
// the request, so it must move to the next candidate instead of failing the client.
func TestForward403RetriesTheNextAccount(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(403)
		_, _ = io.WriteString(w, `{"error":{"type":"permission_error"}}`)
	}))
	defer upstream.Close()

	h := testHandler(upstream.URL)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	cand := control.Candidate{AccountID: 1, Type: "OAUTH"}
	res := h.forward(context.Background(), httptest.NewRecorder(), req, cand, []control.Candidate{cand, cand}, 0, nil, nil, []byte(`{}`), true, false)
	if !res.retry {
		t.Fatal("403 with another candidate left should retry")
	}
	if res.report.Status != 403 {
		t.Errorf("report status = %d", res.report.Status)
	}

	// …and still reaches the client when there is nobody else to try.
	rec := httptest.NewRecorder()
	last := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(`{}`), false, false)
	if last.retry || rec.Code != 403 {
		t.Errorf("last attempt: retry=%v status=%d, want false/403", last.retry, rec.Code)
	}
}

// Everything Anthropic prices beyond the four flat token counters has to survive the trip from
// the stream into the usage report, or it is money the proxy never charges for.
func TestForwardSSEReportsPricedExtras(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		_, _ = io.WriteString(w, "event: message_start\n"+
			`data: {"type":"message_start","message":{"model":"claude-opus-4-8","usage":{`+
			`"input_tokens":300,"cache_creation_input_tokens":5000,`+
			`"cache_creation":{"ephemeral_1h_input_tokens":4000,"ephemeral_5m_input_tokens":1000},`+
			`"server_tool_use":{"web_search_requests":2,"web_fetch_requests":1},"speed":"fast"}}}`+"\n\n"+
			"event: message_delta\n"+
			`data: {"type":"message_delta","usage":{"output_tokens":77}}`+"\n\n")
	}))
	defer upstream.Close()

	h := testHandler(upstream.URL)
	// The request asked for a different model than the one that answered (refusal fallback).
	body := `{"model":"claude-fable-5","stream":true}`
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(body))
	cand := control.Candidate{AccountID: 3, Type: "OAUTH"}
	res := h.forward(context.Background(), httptest.NewRecorder(), req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(body), false, false)

	r := res.report
	if r.Input != 300 || r.Output != 77 || r.CacheWrite != 5000 || r.CacheWrite1h != 4000 {
		t.Errorf("tokens = %+v, want in 300 / out 77 / write 5000 (1h 4000)", r)
	}
	if r.WebSearchRequests != 2 || r.WebFetchRequests != 1 {
		t.Errorf("server tools = %d/%d, want 2/1", r.WebSearchRequests, r.WebFetchRequests)
	}
	if !r.Fast {
		t.Error("fast mode not reported — the response would be priced at the standard tier")
	}
	if r.Model == nil || *r.Model != "claude-opus-4-8" {
		t.Errorf("model = %v, want the model that answered", r.Model)
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
	res := h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(`{}`), false, false)

	if res.retry {
		t.Fatal("last attempt must pass through, not retry")
	}
	if rec.Code != 529 {
		t.Errorf("client status = %d, want 529", rec.Code)
	}
}
