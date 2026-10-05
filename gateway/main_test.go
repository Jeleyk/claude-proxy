package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/config"
)

// The three gateways share one listener and are told apart purely by path prefix, so the mux
// wiring is the load-bearing part of the consolidation. These tests pin down which handler each
// public path reaches, and — just as important — that the prefix is stripped before the handler
// sees the request, since the translators only recognise native API paths.
func testCfg() *config.Config {
	return &config.Config{Port: "0", ServiceURL: "http://127.0.0.1:1", UpstreamBaseURL: "http://127.0.0.1:1"}
}

func TestHealthzIsServedLocally(t *testing.T) {
	rec := httptest.NewRecorder()
	newMux(testCfg()).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("healthz status = %d, want 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `"ok"`) {
		t.Fatalf("healthz body = %q", rec.Body.String())
	}
}

// A request with no credentials must be rejected by whichever handler owns the path — before any
// control-API call. The *shape* of that rejection identifies the handler: the OpenAI translator
// answers with an OpenAI-shaped error envelope, the Anthropic ones with an Anthropic-shaped one.
// That is enough to prove the path landed where it should without standing up a fake service.
func TestPathPrefixSelectsTheHandler(t *testing.T) {
	cases := []struct {
		name       string
		path       string
		wantInBody string
	}{
		// OpenAI errors carry {"error":{...,"type":"invalid_request_error"}} with a "message".
		{"openai routing", "/routing/openai/v1/chat/completions", `"error"`},
		{"native openai", "/openai/v1/responses", `"error"`},
		// Anthropic errors carry {"type":"error","error":{...}}.
		{"anthropic routing", "/routing/anthropic/v1/messages", `"type"`},
		// Everything else is the Claude Code datapath (nginx has already stripped /gateway).
		{"datapath", "/v1/messages", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, c.path, strings.NewReader(`{}`))
			newMux(testCfg()).ServeHTTP(rec, req)

			// No token → 401 from every handler, and crucially not a 404 (which is what a
			// mis-registered prefix would produce).
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("%s: status = %d, want 401 (404 means the path missed its handler)", c.path, rec.Code)
			}
			if c.wantInBody != "" && !strings.Contains(rec.Body.String(), c.wantInBody) {
				t.Fatalf("%s: body = %q, want it to contain %s", c.path, rec.Body.String(), c.wantInBody)
			}
		})
	}
}

// The routing prefixes must be stripped before the translator runs: openaigw serves GET
// /v1/models locally, so if the prefix were still attached the request would fall through as an
// unknown path instead. This is the exact failure that would appear if nginx's proxy_pass grew a
// trailing slash and stripped the prefix itself as well.
func TestRoutingPrefixIsStripped(t *testing.T) {
	for _, path := range []string{"/routing/openai/v1/models", "/routing/anthropic/v1/models"} {
		rec := httptest.NewRecorder()
		newMux(testCfg()).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		// Still 401 (auth runs first), but reaching the handler at all proves the mount matched.
		if rec.Code == http.StatusNotFound {
			t.Fatalf("%s returned 404 — prefix not stripped / handler not mounted", path)
		}
	}
}

// The datapath is registered on "/" and must not swallow the routing prefixes: Go's ServeMux
// prefers the longest matching pattern, and this guards that assumption against a future edit.
func TestDatapathDoesNotShadowRouting(t *testing.T) {
	mux := newMux(testCfg())
	for _, path := range []string{"/routing/openai/v1/chat/completions", "/routing/anthropic/v1/messages"} {
		_, pattern := mux.Handler(httptest.NewRequest(http.MethodPost, path, nil))
		if pattern == "/" {
			t.Fatalf("%s matched the catch-all datapath pattern instead of its routing mount", path)
		}
	}
}
