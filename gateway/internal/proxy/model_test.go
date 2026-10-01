package proxy

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

func TestOverrideModelReplacesOnlyTopLevelModel(t *testing.T) {
	body := `{"messages":[{"role":"user","content":[{"type":"tool_use","input":{"model":"keep-me"}}]}],` +
		`"model" :  "claude-opus-5","stream":true}`
	h := http.Header{}
	out := overrideModel([]byte(body), h, "claude-opus-5-5")

	want := strings.Replace(body, `"claude-opus-5"`, `"claude-opus-5-5"`, 1)
	if string(out) != want {
		t.Fatalf("body =\n%s\nwant\n%s", out, want)
	}
	if h.Get("anthropic-beta") != "" {
		t.Errorf("no [1m] suffix, yet anthropic-beta = %q", h.Get("anthropic-beta"))
	}
}

func TestOverrideModelOneMAddsBetaOnce(t *testing.T) {
	h := http.Header{}
	h.Set("anthropic-beta", "oauth-2025-04-20,"+context1MBeta)
	out := overrideModel([]byte(`{"model":"claude-haiku-4-5-20251001","max_tokens":1}`), h, "claude-opus-5-5[1M]")

	if got := modelFromRequest(out); got == nil || *got != "claude-opus-5-5" {
		t.Fatalf("model = %v, want claude-opus-5-5 without the suffix", got)
	}
	if got := h.Get("anthropic-beta"); got != "oauth-2025-04-20,"+context1MBeta {
		t.Errorf("anthropic-beta = %q", got)
	}
}

func TestOverrideModelLeavesModellessBodiesAlone(t *testing.T) {
	for _, body := range []string{``, `not json`, `[]`, `{"messages":[]}`, `{"nested":{"model":"x"}}`} {
		h := http.Header{}
		if out := overrideModel([]byte(body), h, "claude-opus-5-5[1m]"); string(out) != body {
			t.Errorf("%q rewritten to %q", body, out)
		}
		if h.Get("anthropic-beta") != "" {
			t.Errorf("%q: beta added to a request that has no model", body)
		}
	}
}

func TestOverrideModelKeepsBodyValidJSON(t *testing.T) {
	out := overrideModel([]byte(`{"model":"a\"b","x":1}`), http.Header{}, "claude-opus-5-5")
	var v map[string]any
	if err := json.Unmarshal(out, &v); err != nil || v["model"] != "claude-opus-5-5" || v["x"] != float64(1) {
		t.Fatalf("out = %s (%v)", out, err)
	}
}

// End to end through the handler: the token's model reaches upstream on every candidate, with
// the 1M beta alongside the account's own betas.
func TestHandlerAppliesTokenDefaultModel(t *testing.T) {
	type seen struct{ model, beta string }
	var got []seen
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		m := modelFromRequest(b)
		got = append(got, seen{*m, r.Header.Get("anthropic-beta")})
		if len(got) == 1 {
			w.WriteHeader(529)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"model":"claude-opus-5-5","usage":{"input_tokens":1}}`)
	}))
	defer upstream.Close()

	ctl := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/resolve" {
			_, _ = fmt.Fprint(w, `{"userId":1,"overLimit":false,"defaultModel":"claude-opus-5-5[1m]","candidates":[
				{"accountId":1,"type":"OAUTH","authHeaders":{"anthropic-beta":"oauth-2025-04-20"}},
				{"accountId":2,"type":"OAUTH","authHeaders":{"anthropic-beta":"oauth-2025-04-20"}}]}`)
			return
		}
		w.WriteHeader(204)
	}))
	defer ctl.Close()

	h := NewHandler(&config.Config{UpstreamBaseURL: upstream.URL}, control.New(ctl.URL, "tok"))
	req := httptest.NewRequest("POST", "/v1/messages?beta=true", strings.NewReader(`{"model":"claude-opus-5","max_tokens":1}`))
	req.Header.Set("Authorization", "Bearer cxp_token")
	req.Header.Set("anthropic-beta", "claude-code-20250219")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != 200 || len(got) != 2 {
		t.Fatalf("status %d after %d upstream calls", rec.Code, len(got))
	}
	for i, s := range got {
		if s.model != "claude-opus-5-5" {
			t.Errorf("attempt %d: model %q", i, s.model)
		}
		if s.beta != "claude-code-20250219,"+context1MBeta+",oauth-2025-04-20" {
			t.Errorf("attempt %d: anthropic-beta %q", i, s.beta)
		}
	}
}
