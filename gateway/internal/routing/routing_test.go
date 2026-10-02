package routing_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"claudeproxy/gateway/internal/anthropicgw"
	"claudeproxy/gateway/internal/ccident"
	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/routing"
)

// TestRoutingEndToEnd exercises the full loop against stub control + upstream servers: token
// auth, candidate resolution, Claude Code prompt + auth-header injection, response relay, and
// usage reporting (source="routing").
func TestRoutingEndToEnd(t *testing.T) {
	reports := make(chan control.UsageReport, 4)

	controlSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Internal-Token") != "secret" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/internal/resolve":
			var req struct {
				Token, Method, Path, Source string
			}
			_ = json.NewDecoder(r.Body).Decode(&req)
			if req.Source != "routing" {
				t.Errorf("resolve source = %q, want routing", req.Source)
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"userId":    1,
				"overLimit": false,
				"candidates": []map[string]any{{
					"accountId":   7,
					"type":        "OAUTH",
					"deviceId":    "acct-device",
					"accountUuid": "acct-uuid",
					"authHeaders": map[string]string{"Authorization": "Bearer sk-test", "anthropic-beta": "oauth-2025-04-20"},
				}},
			})
		case "/internal/usage":
			var rep control.UsageReport
			_ = json.NewDecoder(r.Body).Decode(&rep)
			reports <- rep
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer controlSrv.Close()

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/messages" {
			t.Errorf("upstream path = %s", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer sk-test" {
			t.Errorf("auth header = %q", got)
		}
		if r.Header.Get("anthropic-version") == "" {
			t.Errorf("missing anthropic-version")
		}
		body, _ := io.ReadAll(r.Body)
		var parsed struct {
			System   []map[string]string `json:"system"`
			Metadata map[string]string   `json:"metadata"`
		}
		_ = json.Unmarshal(body, &parsed)
		if len(parsed.System) == 0 || parsed.System[0]["text"] != ccident.SystemPrompt {
			t.Errorf("Claude Code system prompt not injected: %s", body)
		}
		// The client's own metadata must not reach upstream; the account's identity must.
		var ident map[string]string
		_ = json.Unmarshal([]byte(parsed.Metadata["user_id"]), &ident)
		sid := r.Header.Get("X-Claude-Code-Session-Id")
		if ident["device_id"] != "acct-device" || ident["account_uuid"] != "acct-uuid" || sid == "" || ident["session_id"] != sid {
			t.Errorf("identity not stamped: metadata=%v session header=%q", parsed.Metadata, sid)
		}
		if r.Header.Get("x-app") != "cli" || r.Header.Get("User-Agent") != "claude-cli/test" {
			t.Errorf("client headers wrong: x-app=%q ua=%q", r.Header.Get("x-app"), r.Header.Get("User-Agent"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"msg_1","model":"claude-sonnet-4-5","content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn","usage":{"input_tokens":11,"output_tokens":3}}`))
	}))
	defer upstream.Close()

	cfg := &config.Config{UpstreamBaseURL: upstream.URL, ClaudeCodeUserAgent: "claude-cli/test"}
	ctrl := control.New(controlSrv.URL, "secret").WithSource("routing")
	h := routing.NewHandler(cfg, ctrl, anthropicgw.New())

	req := httptest.NewRequest(http.MethodPost, "/v1/messages", strings.NewReader(`{"model":"claude-sonnet-4-5","metadata":{"user_id":"sdk-caller"},"messages":[{"role":"user","content":"hi"}]}`))
	req.Header.Set("x-api-key", "cxr_test")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), `"text":"hi"`) {
		t.Fatalf("client did not get upstream body: %s", rr.Body.String())
	}

	select {
	case rep := <-reports:
		if rep.AccountID != 7 || rep.Source != "routing" || rep.Status != 200 {
			t.Fatalf("usage report wrong: %+v", rep)
		}
		if rep.Input != 11 || rep.Output != 3 {
			t.Fatalf("usage tokens wrong: %+v", rep)
		}
		if rep.Model == nil || *rep.Model != "claude-sonnet-4-5" {
			t.Fatalf("usage model wrong: %+v", rep.Model)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("no usage report received")
	}
}

// TestMissingTokenUnauthorized ensures a request with no credentials is rejected before resolve.
func TestMissingTokenUnauthorized(t *testing.T) {
	cfg := &config.Config{UpstreamBaseURL: "http://127.0.0.1:0"}
	ctrl := control.New("http://127.0.0.1:0", "secret").WithSource("routing")
	h := routing.NewHandler(cfg, ctrl, anthropicgw.New())
	req := httptest.NewRequest(http.MethodPost, "/v1/messages", strings.NewReader(`{}`))
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rr.Code)
	}
}
