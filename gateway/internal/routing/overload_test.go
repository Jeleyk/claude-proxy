package routing_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/openaigw"
	"claudeproxy/gateway/internal/routing"
)

// Anthropic can accept a request with a 200 and only then fail it in-stream with
// `overloaded_error`. Before this was handled, the routing gateway translated that straight to the
// client — an OpenAI caller saw {"error":{"code":null,"message":"Overloaded",...}} even though
// another account could have served the request.
const overloadFrame = "event: error\n" +
	`data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}` + "\n\n"

// stubControl serves /internal/resolve with [n] identical candidates and swallows usage reports.
func stubControl(t *testing.T, n int) *httptest.Server {
	t.Helper()
	cands := make([]map[string]any, n)
	for i := range cands {
		cands[i] = map[string]any{
			"accountId":   i + 1,
			"type":        "OAUTH",
			"authHeaders": map[string]string{"Authorization": "Bearer sk-test"},
		}
	}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/resolve" {
			_ = json.NewEncoder(w).Encode(map[string]any{"userId": 1, "overLimit": false, "candidates": cands})
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
}

func TestRoutingSwapsAccountOnInStreamOverload(t *testing.T) {
	var calls int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		if atomic.AddInt32(&calls, 1) == 1 {
			_, _ = io.WriteString(w, overloadFrame)
			return
		}
		_, _ = io.WriteString(w,
			"event: message_start\n"+`data: {"type":"message_start","message":{"id":"msg_2","model":"claude-sonnet-4-5","usage":{"input_tokens":5,"output_tokens":0}}}`+"\n\n"+
				"event: content_block_start\n"+`data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}`+"\n\n"+
				"event: content_block_delta\n"+`data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"served-by-second"}}`+"\n\n"+
				"event: message_stop\n"+`data: {"type":"message_stop"}`+"\n\n")
	}))
	defer upstream.Close()

	controlSrv := stubControl(t, 2)
	defer controlSrv.Close()

	h := routing.NewHandler(
		&config.Config{UpstreamBaseURL: upstream.URL},
		control.New(controlSrv.URL, "secret").WithSource("routing"),
		openaigw.New(),
	)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions",
		strings.NewReader(`{"model":"claude-sonnet-4-5","stream":true,"messages":[{"role":"user","content":"hi"}]}`))
	req.Header.Set("Authorization", "Bearer cxr_test")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)

	if got := atomic.LoadInt32(&calls); got != 2 {
		t.Fatalf("upstream calls = %d, want 2 (the overload should have moved to account 2)", got)
	}
	body := rr.Body.String()
	if !strings.Contains(body, "served-by-second") {
		t.Errorf("client did not receive the second account's stream: %q", body)
	}
	if strings.Contains(strings.ToLower(body), "overloaded") {
		t.Errorf("upstream overload leaked to the client: %q", body)
	}
}

// With no account left to try, the failure is the client's to see — the request must not hang or
// truncate silently.
func TestRoutingPassesOverloadThroughOnLastCandidate(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, overloadFrame)
	}))
	defer upstream.Close()

	controlSrv := stubControl(t, 1)
	defer controlSrv.Close()

	h := routing.NewHandler(
		&config.Config{UpstreamBaseURL: upstream.URL},
		control.New(controlSrv.URL, "secret").WithSource("routing"),
		openaigw.New(),
	)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions",
		strings.NewReader(`{"model":"claude-sonnet-4-5","stream":true,"messages":[{"role":"user","content":"hi"}]}`))
	req.Header.Set("Authorization", "Bearer cxr_test")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)

	body := rr.Body.String()
	if rr.Body.Len() == 0 {
		t.Fatal("client got an empty stream; the error should be reported")
	}
	// The client protocol's own error shape, not a silent truncation.
	if !strings.Contains(strings.ToLower(body), "overload") && !strings.Contains(body, "error") {
		t.Errorf("no error surfaced to the client: %q", body)
	}
}
