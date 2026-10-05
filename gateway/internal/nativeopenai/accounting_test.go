package nativeopenai

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/control"
)

type cancelAfterDelta struct {
	*httptest.ResponseRecorder
	cancel context.CancelFunc
}

func (w *cancelAfterDelta) Write(b []byte) (int, error) {
	n, err := w.ResponseRecorder.Write(b)
	if strings.Contains(string(b), "useful output") {
		w.cancel()
	}
	return n, err
}

func TestCancelledUsefulOutputIsNotReportedAsKnownFreeUsage(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		sse(w, "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_cancel\",\"usage\":null}}\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"useful output\"}\n\n")
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}, account(1, "API_KEY"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	req := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(`{"model":"gpt-test","input":"hello","stream":true}`)).WithContext(ctx)
	req.Header.Set("Authorization", "Bearer cxr_client")
	rec := &cancelAfterDelta{httptest.NewRecorder(), cancel}
	f.h.ServeHTTP(rec, req)
	report := f.report(t)
	if !strings.Contains(rec.Body.String(), "useful output") || report.Status != 499 || !report.AccountingIncomplete {
		t.Fatalf("cancelled output incorrectly accounted: %+v", report)
	}
	if report.Input != 0 || report.Output != 0 {
		t.Fatal("fabricated usage")
	}
}

func TestCompletedResponseAccountingCompleteness(t *testing.T) {
	for _, tc := range []struct {
		name, extra, usage string
		incomplete         bool
	}{
		{"normal", `"service_tier":"default",`, `{"input_tokens":10,"output_tokens":2}`, false},
		{"genuine zero", ``, `{"input_tokens":0,"output_tokens":0}`, false},
		{"missing", ``, `null`, true},
		{"empty", ``, `{}`, true},
		{"partial", ``, `{"input_tokens":10}`, true},
		{"invalid", ``, `{"input_tokens":-1,"output_tokens":2}`, true},
		{"priority", `"service_tier":"priority",`, `{"input_tokens":10,"output_tokens":2}`, true},
		{"hosted tool", `"output":[{"type":"web_search_call"}],`, `{"input_tokens":10,"output_tokens":2}`, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := setup(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				io.WriteString(w, `{"id":"resp_test",`+tc.extra+`"usage":`+tc.usage+`}`)
			}, account(1, "API_KEY"))
			if rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":"hello"}`); rec.Code != 200 {
				t.Fatal(rec.Code, rec.Body)
			}
			if report := f.report(t); report.AccountingIncomplete != tc.incomplete {
				t.Fatalf("report %+v", report)
			}
		})
	}
}

func TestHostedFeaturesRemainExplicitlyUnpriced(t *testing.T) {
	for _, body := range []string{
		`{"model":"gpt-test","input":"hello","service_tier":"priority"}`,
		`{"model":"gpt-test","input":"hello","tools":[{"type":"web_search"}]}`,
		`{"model":"gpt-test","input":"hello","tools":[{"type":"code_interpreter","container":{"type":"auto"}}]}`,
	} {
		f := setup(t, func(w http.ResponseWriter, r *http.Request) { sse(w, completed) }, account(1, "OAUTH"))
		if rec := f.call("POST", "/v1/responses", body); rec.Code != 200 {
			t.Fatal(rec.Code, rec.Body)
		}
		report := f.report(t)
		if !report.AccountingIncomplete || report.Input != 40 || report.Output != 20 {
			t.Fatalf("report %+v", report)
		}
	}
}

func TestUnsupportedMeteringPlanFailsBeforeSharedUpstream(t *testing.T) {
	f := setup(t, func(w http.ResponseWriter, r *http.Request) { t.Error("shared upstream must not be reached") })
	ctl := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/resolve" {
			json.NewEncoder(w).Encode(control.ResolveResp{MeteringUnsupported: true})
		} else {
			w.WriteHeader(204)
		}
	}))
	defer ctl.Close()
	f.h.ctrl = control.New(ctl.URL, "internal")
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":"hello"}`)
	if rec.Code != 403 || !strings.Contains(rec.Body.String(), "metering_unsupported") {
		t.Fatal(rec.Code, rec.Body)
	}
}

func TestClientExecutedToolsDoNotMakeTokenAccountingUnknown(t *testing.T) {
	for _, body := range []string{
		`{"tools":[{"type":"shell","environment":{"type":"local"}}]}`,
		`{"tools":[{"type":"namespace","name":"local","tools":[{"type":"function","name":"f","parameters":{"type":"object","properties":{"tools":{"type":"array"}}}}]}]}`,
		`{"input":[{"type":"function_call_output","call_id":"c","output":{"tools":[{"type":"application_data"}]}}]}`,
	} {
		var parsed map[string]json.RawMessage
		if err := json.Unmarshal([]byte(body), &parsed); err != nil {
			t.Fatal(err)
		}
		if unmeteredRequest(parsed) {
			t.Fatal("client-side tool unexpectedly unpriced", body)
		}
	}
}
