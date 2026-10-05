package nativeopenai

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

type fixture struct {
	h        *Handler
	reports  chan control.UsageReport
	resolved chan map[string]any
}

func setup(t *testing.T, upstream http.HandlerFunc, candidates ...control.Candidate) *fixture {
	t.Helper()
	upstreamServer := httptest.NewServer(upstream)
	t.Cleanup(upstreamServer.Close)
	f := &fixture{reports: make(chan control.UsageReport, 10), resolved: make(chan map[string]any, 10)}
	ctl := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Internal-Token") != "internal" {
			t.Errorf("missing internal authentication")
		}
		switch r.URL.Path {
		case "/internal/resolve":
			var request map[string]any
			_ = json.NewDecoder(r.Body).Decode(&request)
			f.resolved <- request
			if request["token"] != "cxr_client" {
				w.WriteHeader(401)
				return
			}
			uid, tid := 12, 14
			prompt := "operator instructions"
			_ = json.NewEncoder(w).Encode(control.ResolveResp{UserID: &uid, TokenID: &tid, Candidates: candidates, SystemPrompt: &prompt})
		case "/internal/usage":
			var report control.UsageReport
			_ = json.NewDecoder(r.Body).Decode(&report)
			f.reports <- report
			w.WriteHeader(204)
		case "/internal/session-end":
			w.WriteHeader(204)
		default:
			t.Errorf("unexpected control path %s", r.URL.Path)
		}
	}))
	t.Cleanup(ctl.Close)
	cfg := &config.Config{OpenAIAPIBaseURL: upstreamServer.URL + "/api/v1", OpenAICodexBaseURL: upstreamServer.URL + "/codex", OpenAIClientVersion: "1.2.3", UpstreamStallTimeout: time.Second}
	f.h = NewHandler(cfg, control.New(ctl.URL, "internal").WithSource("routing").WithProvider("OPENAI"))
	return f
}
func account(id int, kind string) control.Candidate {
	return control.Candidate{AccountID: id, Provider: "OPENAI", Type: kind, AuthHeaders: map[string]string{"Authorization": fmt.Sprintf("Bearer upstream-%d", id), "ChatGPT-Account-Id": "workspace-owned"}}
}
func (f *fixture) call(method, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer cxr_client")
	req.Header.Set("Cookie", "leak-cookie")
	req.Header.Set("Forwarded", "leak-forwarded")
	req.Header.Set("X-Forwarded-For", "leak-ip")
	req.Header.Set("ChatGPT-Account-Id", "leak-workspace")
	req.Header.Set("OpenAI-Project", "leak-project")
	req.Header.Set("User-Agent", "leak-ua")
	rec := httptest.NewRecorder()
	f.h.ServeHTTP(rec, req)
	return rec
}
func (f *fixture) report(t *testing.T) control.UsageReport {
	t.Helper()
	select {
	case r := <-f.reports:
		return r
	case <-time.After(3 * time.Second):
		t.Fatal("usage report missing")
		return control.UsageReport{}
	}
}

const completed = `event: response.completed
data: {"type":"response.completed","response":{"id":"resp_done","object":"response","model":"canonical-gpt","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}],"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":60},"output_tokens":20,"output_tokens_details":{"reasoning_tokens":12}}}}

`

func sse(w http.ResponseWriter, data string) {
	w.Header().Set("Content-Type", "text/event-stream")
	_, _ = io.WriteString(w, data)
}

func TestOAuthNonStreamingNormalizesAndAccountsOnce(t *testing.T) {
	var seen map[string]json.RawMessage
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/codex/responses" {
			t.Errorf("path %s", r.URL.Path)
		}
		_ = json.NewDecoder(r.Body).Decode(&seen)
		if r.Header.Get("Authorization") != "Bearer upstream-1" || r.Header.Get("ChatGPT-Account-Id") != "workspace-owned" {
			t.Error("wrong upstream identity")
		}
		for _, key := range []string{"Cookie", "Forwarded", "X-Forwarded-For", "OpenAI-Project"} {
			if r.Header.Get(key) != "" {
				t.Errorf("client header leaked: %s", key)
			}
		}
		if r.Header.Get("User-Agent") == "leak-ua" {
			t.Error("client user-agent leaked")
		}
		w.Header().Set("X-Codex-Primary-Used-Percent", "42")
		w.Header().Set("Set-Cookie", "must-not-leak")
		sse(w, completed)
	}, account(1, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":"hello","instructions":"client","stream":false}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"id":"resp_done"`) || strings.Contains(rec.Body.String(), "event:") {
		t.Fatalf("response %d %s", rec.Code, rec.Body)
	}
	if string(seen["stream"]) != "true" || string(seen["store"]) != "false" || string(seen["input"])[0] != '[' {
		t.Errorf("wrong normalized request %v", seen)
	}
	var instructions string
	_ = json.Unmarshal(seen["instructions"], &instructions)
	if instructions != "operator instructions\n\nclient" {
		t.Errorf("instructions %q", instructions)
	}
	report := f.report(t)
	if report.Input != 40 || report.CacheRead != 60 || report.Output != 20 || report.Status != 200 || report.Model == nil || *report.Model != "gpt-test" {
		t.Fatalf("usage %+v", report)
	}
	if report.RatelimitHeaders["x-codex-primary-used-percent"] != "42" || rec.Header().Get("Set-Cookie") != "" {
		t.Error("incorrect response headers")
	}
	request := <-f.resolved
	if request["provider"] != "OPENAI" || request["source"] != "routing" || request["model"] != "gpt-test" {
		t.Fatalf("resolve %v", request)
	}
}

func TestAPIKeyNonStreamingPassesToolsAndReasoning(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/responses" {
			t.Errorf("path %s", r.URL.Path)
		}
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), `"effort":"high"`) || !strings.Contains(string(b), `"name":"test"`) {
			t.Error("tools or reasoning lost")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"response","usage":{"input_tokens":5,"output_tokens":2}}`)
	}, account(1, "API_KEY"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"tools":[{"type":"function","name":"test","parameters":{}}],"reasoning":{"effort":"high"}}`)
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	report := f.report(t)
	if report.Input != 5 || report.Output != 2 {
		t.Fatal(report)
	}
}

func TestHTTPFailoverBeforeVisibleOutput(t *testing.T) {
	var calls atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") == "Bearer upstream-1" {
			w.Header().Set("X-Codex-Primary-Used-Percent", "100")
			w.WriteHeader(429)
			_, _ = io.WriteString(w, "secret account rejected")
			return
		}
		sse(w, "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"name\":\"test\",\"call_id\":\"call_1\"}}\n\n"+completed)
	}, account(1, "OAUTH"), account(2, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "function_call") || strings.Contains(rec.Body.String(), "rejected") || calls.Load() != 2 {
		t.Fatalf("bad retry %d %s", rec.Code, rec.Body)
	}
	reports := map[int]control.UsageReport{}
	for i := 0; i < 2; i++ {
		r := f.report(t)
		reports[r.AccountID] = r
	}
	if reports[1].Status != 429 || reports[1].Input != 0 || reports[2].Input != 40 || reports[1].RatelimitHeaders["x-codex-primary-used-percent"] != "100" {
		t.Fatal(reports)
	}
}

func TestSSEFailoverDoesNotMixVisibleResponses(t *testing.T) {
	for _, visible := range []bool{false, true} {
		t.Run(fmt.Sprint(visible), func(t *testing.T) {
			var calls atomic.Int32
			f := setup(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.Header.Get("Authorization") == "Bearer upstream-1" {
					first := ""
					if visible {
						first = "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"first\"}}\n\n"
					}
					sse(w, first+"event: error\ndata: {\"type\":\"error\",\"code\":\"rate_limit_exceeded\"}\n\n")
					return
				}
				sse(w, completed)
			}, account(1, "OAUTH"), account(2, "OAUTH"))
			rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
			if visible {
				if calls.Load() != 1 || strings.Contains(rec.Body.String(), "resp_done") {
					t.Fatal("mixed responses", rec.Body.String())
				}
				if f.report(t).Status != 429 {
					t.Fatal("wrong report")
				}
			} else {
				if calls.Load() != 2 || !strings.Contains(rec.Body.String(), "resp_done") || strings.Contains(rec.Body.String(), "rate_limit_exceeded") {
					t.Fatal("missing retry", rec.Body.String())
				}
				f.report(t)
				f.report(t)
			}
		})
	}
}

func TestRedirectNeverLeaksCredentials(t *testing.T) {
	var leaked atomic.Int32
	evil := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked.Add(1) }))
	defer evil.Close()
	f := setup(t, func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, evil.URL, 302) }, account(1, "API_KEY"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[]}`)
	if rec.Code != 502 || leaked.Load() != 0 || rec.Header().Get("Location") != "" {
		t.Fatalf("unsafe redirect %d", rec.Code)
	}
	f.report(t)
}

func TestOnlyNativePathsAndStatelessRequests(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) { t.Error("must not call upstream") }, account(1, "OAUTH"))
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"POST", "/v1/responses/", "{}", 404}, {"POST", "/v1/%72esponses", "{}", 404}, {"GET", "/v1/responses", "", 405},
		{"POST", "/v1/chat/completions", "{}", 404}, {"POST", "/v1/responses?url=http://evil", "{}", 400},
		{"POST", "/v1/responses", `{"model":"m","input":[],"previous_response_id":"response"}`, 400},
		{"POST", "/v1/responses", `{"model":"m","input":[],"conversation":"conversation"}`, 400},
		{"POST", "/v1/responses", `{"model":"m","input":[],"background":true}`, 400},
		{"POST", "/v1/responses", `{"model":"m","input":[],"store":true}`, 400},
		{"POST", "/v1/responses", `[]`, 400},
	} {
		rec := f.call(tc.method, tc.path, tc.body)
		if rec.Code != tc.status {
			t.Errorf("%s %s %s: %d", tc.method, tc.path, tc.body, rec.Code)
		}
	}
	req := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer cxp_invalid")
	rec := httptest.NewRecorder()
	f.h.ServeHTTP(rec, req)
	if rec.Code != 401 {
		t.Fatal(rec.Code)
	}
	rec = f.call("POST", "/v1/responses", strings.Repeat("x", maxBody+1))
	if rec.Code != 413 {
		t.Fatal("body cap", rec.Code)
	}
}

func TestProviderIsolationAndScopedModels(t *testing.T) {
	var calls atomic.Int32
	anthropic := account(1, "OAUTH")
	anthropic.Provider = "ANTHROPIC"
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/codex/models" || r.URL.Query().Get("client_version") != "1.2.3" || r.Header.Get("Authorization") != "Bearer upstream-2" {
			t.Error("wrong catalog request")
		}
		_, _ = io.WriteString(w, `{"models":[{"slug":"gpt-entitled","display_name":"Entitled"}]}`)
	}, anthropic, account(2, "OAUTH"))
	rec := f.call("GET", "/v1/models", "")
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"id":"gpt-entitled"`) || !strings.Contains(rec.Body.String(), `"slug":"gpt-entitled"`) || calls.Load() != 1 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	if !f.report(t).Free {
		t.Fatal("models incorrectly metered")
	}
}

func TestTruncatedStreamProducesFramedError(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		sse(w, "event: response.created\ndata: {\"type\":\"response.created\"}\n\ndata: {\"broken\":")
	}, account(1, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if strings.Contains(rec.Body.String(), "broken") || !strings.Contains(rec.Body.String(), "event: error\ndata:") {
		t.Fatal("unframed error", rec.Body.String())
	}
	if f.report(t).Status != 502 {
		t.Fatal("missing failure report")
	}
}

func TestStreamWatchdogAndRateEvent(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		sse(w, "event: codex.rate_limits\ndata: {\"type\":\"codex.rate_limits\",\"rate_limits\":{\"primary\":{\"used_percent\":65,\"window_minutes\":300,\"reset_at\":1999999999}}}\n\n")
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}, account(1, "OAUTH"))
	f.h.cfg.UpstreamStallTimeout = 20 * time.Millisecond
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	report := f.report(t)
	if report.Status != 504 || report.RatelimitHeaders["x-codex-primary-used-percent"] != "65" || !strings.Contains(rec.Body.String(), "stalled") {
		t.Fatal(report, rec.Body.String())
	}
}

func TestClientCancellationClosesUpstream(t *testing.T) {
	started := make(chan struct{})
	closed := make(chan struct{})
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		sse(w, "event: response.created\ndata: {\"type\":\"response.created\"}\n\n")
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
		close(closed)
	}, account(1, "OAUTH"))
	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(`{"model":"gpt-test","input":[],"stream":true}`)).WithContext(ctx)
	req.Header.Set("Authorization", "Bearer cxr_client")
	done := make(chan struct{})
	go func() { f.h.ServeHTTP(httptest.NewRecorder(), req); close(done) }()
	<-started
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("gateway did not cancel")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("upstream did not cancel")
	}
	if f.report(t).Status != 499 {
		t.Fatal("cancellation not recorded")
	}
}

func TestStaticOAuthUsesCodexAndFiltersHiddenModels(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			t.Error("static OAuth routed to API key backend")
		}
		if r.URL.Path == "/codex/models" {
			_, _ = io.WriteString(w, `{"models":[{"slug":"visible","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"disabled","enabled":false}]}`)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["stream"] != true || body["store"] != false {
			t.Error("static OAuth normalization missing")
		}
		sse(w, completed)
	}, account(1, "OAUTH_STATIC"))
	rec := f.call("GET", "/v1/models", "")
	if rec.Code != 200 || strings.Contains(rec.Body.String(), "hidden") || strings.Contains(rec.Body.String(), "disabled") || !strings.Contains(rec.Body.String(), "visible") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	rec = f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":false}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "resp_done") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
}

func TestEarlyHeadRetainsSafeFailover(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer upstream-1" {
			time.Sleep(30 * time.Millisecond)
			w.WriteHeader(503)
			return
		}
		sse(w, completed)
	}, account(1, "OAUTH"), account(2, "OAUTH"))
	f.h.cfg.EarlyHeadTimeout = time.Millisecond
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), ": keep-alive\n\n") || !strings.Contains(rec.Body.String(), "resp_done") || strings.Contains(rec.Body.String(), "rejected") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	f.report(t)
}

func TestStreamFragmentsAndCRLFRemainWhole(t *testing.T) {
	data := strings.ReplaceAll(completed, "\n", "\r\n")
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		for _, c := range []byte(data) {
			_, _ = w.Write([]byte{c})
			w.(http.Flusher).Flush()
		}
	}, account(1, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if !strings.Contains(rec.Body.String(), "resp_done") || strings.Contains(rec.Body.String(), "upstream_error") {
		t.Fatal(rec.Body.String())
	}
	f.report(t)
}

func TestModelEntitlementCanFailOver(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer upstream-1" {
			w.WriteHeader(404)
			_, _ = io.WriteString(w, `{"error":{"code":"model_not_found","message":"account details"}}`)
			return
		}
		sse(w, completed)
	}, account(1, "OAUTH"), account(2, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "resp_done") || strings.Contains(rec.Body.String(), "account details") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	f.report(t)
}

func TestStreamErrorDoesNotExposeAccountDetails(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		sse(w, "event: response.created\ndata: {\"type\":\"response.created\"}\n\nevent: error\ndata: {\"type\":\"error\",\"code\":\"rate_limit_exceeded\",\"message\":\"secret-account-details\"}\n\n")
	}, account(1, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if strings.Contains(rec.Body.String(), "secret-account-details") || !strings.Contains(rec.Body.String(), "OpenAI response failed") {
		t.Fatal(rec.Body.String())
	}
	if f.report(t).Status != 429 {
		t.Fatal("missing report")
	}
}

func TestFailedJSONResponseIsReportedAsFailure(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"failed_response","status":"failed","error":{"code":"rate_limit_exceeded","message":"private account detail"},"usage":{"input_tokens":4,"output_tokens":1}}`)
	}, account(1, "API_KEY"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[]}`)
	if rec.Code != 429 || strings.Contains(rec.Body.String(), "private account detail") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	report := f.report(t)
	if report.Status != 429 || report.Input != 4 {
		t.Fatal(report)
	}
}

func TestOversizedSSEIsRejectedWithoutLeakingFragment(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) { sse(w, "data: "+strings.Repeat("x", maxEvent+1)+"\n\n") }, account(1, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if rec.Code != 502 || len(rec.Body.Bytes()) > 1024 {
		t.Fatal("oversized SSE not rejected", rec.Code, rec.Body.Len())
	}
	if f.report(t).Status != 502 {
		t.Fatal("oversized frame not recorded")
	}
}

func TestModelCatalogSkipsUnusableAccount(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer upstream-1" {
			w.WriteHeader(401)
			return
		}
		_, _ = io.WriteString(w, `{"data":[{"id":"gpt-api-only","object":"model","owned_by":"openai"}]}`)
	}, account(1, "OAUTH"), account(2, "API_KEY"))
	rec := f.call("GET", "/v1/models", "")
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "gpt-api-only") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	f.report(t)
}

func TestModelCatalogHonorsOnlyValidatedClientVersion(t *testing.T) {
	var calls atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Query().Get("client_version") != "1.2.3-alpha.1+build.2" {
			t.Errorf("client version lost %s", r.URL.RawQuery)
		}
		if len(r.URL.Query()) != 1 {
			t.Error("arbitrary query forwarded")
		}
		_, _ = io.WriteString(w, `{"models":[{"slug":"gpt-current","visibility":"list"}]}`)
	}, account(1, "OAUTH"))
	rec := f.call("GET", "/v1/models?client_version=1.2.3-alpha.1%2Bbuild.2", "")
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	for _, query := range []string{"client_version=", "client_version=latest", "client_version=1.2.3%26url%3Devil", "client_version=1.2.3&client_version=4.5.6", "client_version=1.2.3&url=evil", "client_version=01.2.3"} {
		rec = f.call("GET", "/v1/models?"+query, "")
		if rec.Code != 400 {
			t.Fatal(query, rec.Code)
		}
	}
	if calls.Load() != 1 {
		t.Fatal("bad catalog query reached upstream")
	}
}
