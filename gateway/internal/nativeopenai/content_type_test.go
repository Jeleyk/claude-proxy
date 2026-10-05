package nativeopenai

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"claudeproxy/gateway/internal/config"
)

// A nil header value suppresses net/http's automatic Content-Type sniffing, matching the
// actual Codex response: HTTP200, no Content-Type or Content-Encoding, event:/data: body.
func missingTypeSSE(w http.ResponseWriter, data string) {
	w.Header()["Content-Type"] = nil
	w.WriteHeader(200)
	_, _ = io.WriteString(w, data)
}

func TestMissingContentTypeCodexSSEStreamingAndBuffered(t *testing.T) {
	for _, stream := range []bool{false, true} {
		t.Run(fmt.Sprint(stream), func(t *testing.T) {
			upstream := `event: response.output_item.done
data: {"type":"response.output_item.done","output_index":0,"item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"headerless answer"}]}}

event: response.completed
data: {"type":"response.completed","response":{"id":"resp_headerless","usage":{"input_tokens":10,"output_tokens":3}}}

`
			f := setup(t, func(w http.ResponseWriter, r *http.Request) { missingTypeSSE(w, upstream) }, account(1, "OAUTH"))
			rec := f.call("POST", "/v1/responses", fmt.Sprintf(`{"model":"gpt-test","input":[],"stream":%t}`, stream))
			if rec.Code != 200 || !strings.Contains(rec.Body.String(), "headerless answer") || !strings.Contains(rec.Body.String(), "resp_headerless") {
				t.Fatal(rec.Code, rec.Body.String())
			}
			if stream {
				if rec.Body.String() != upstream || !strings.Contains(rec.Header().Get("Content-Type"), "text/event-stream") {
					t.Fatal("SSE prefix lost", rec.Body.String())
				}
			} else {
				if strings.Contains(rec.Body.String(), "event:") || rec.Header().Get("Content-Type") != "application/json" {
					t.Fatal("buffered response not JSON", rec.Body.String())
				}
			}
			report := f.report(t)
			if report.Status != 200 || report.Input != 10 || report.Output != 3 {
				t.Fatal(report)
			}
		})
	}
}

func TestMissingContentTypeDoesNotAcceptHTMLOrJSONAsCodexStream(t *testing.T) {
	for _, body := range []string{`<html><body>login page</body></html>`, `{"id":"not-a-stream","output":[]}`, "data: not-json\n\n"} {
		for _, stream := range []bool{false, true} {
			t.Run(fmt.Sprintf("%t/%s", stream, body[:4]), func(t *testing.T) {
				f := setup(t, func(w http.ResponseWriter, r *http.Request) { missingTypeSSE(w, body) }, account(1, "OAUTH"))
				rec := f.call("POST", "/v1/responses", fmt.Sprintf(`{"model":"gpt-test","input":[],"stream":%t}`, stream))
				if rec.Code != 502 || strings.Contains(rec.Body.String(), "login page") || strings.Contains(rec.Body.String(), "not-a-stream") {
					t.Fatal(rec.Code, rec.Body.String())
				}
				if f.report(t).Status != 502 {
					t.Fatal("wrong failure status")
				}
			})
		}
	}
}

func TestSSEPrefixSniffIsBoundedAndNonDestructive(t *testing.T) {
	for _, tc := range []struct {
		body string
		want bool
	}{
		{"event: response.created\ndata: {}\n\n", true},
		{"data: {}\n\n", true}, {": keep-alive\n\n" + completed, true},
		{"\r\n\n" + completed, true}, {"{\"id\":\"json\"}", false},
		{"<html>not sse</html>", false}, {"eventually not an SSE field", false},
		{strings.Repeat("\n", maxSSESniffBytes) + completed, false},
	} {
		reader := bufio.NewReaderSize(strings.NewReader(tc.body), maxSSESniffBytes)
		got, err := sniffSSEPrefix(reader)
		if err != nil || got != tc.want {
			t.Fatal("wrong classification", got, err)
		}
		remaining, err := io.ReadAll(reader)
		if err != nil || string(remaining) != tc.body {
			t.Fatal("sniff consumed or altered bytes")
		}
	}
}

func TestMissingContentTypeSniffHonorsStallAndCancellation(t *testing.T) {
	for _, cancelled := range []bool{false, true} {
		t.Run(fmt.Sprint(cancelled), func(t *testing.T) {
			started := make(chan struct{})
			f := setup(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header()["Content-Type"] = nil
				w.WriteHeader(200)
				w.(http.Flusher).Flush()
				close(started)
				<-r.Context().Done()
			}, account(1, "OAUTH"))
			f.h.cfg.UpstreamStallTimeout = 20 * time.Millisecond
			if cancelled {
				f.h.cfg.UpstreamStallTimeout = time.Second
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			req := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(`{"model":"gpt-test","input":[],"stream":true}`)).WithContext(ctx)
			req.Header.Set("Authorization", "Bearer cxr_client")
			rec := httptest.NewRecorder()
			done := make(chan struct{})
			go func() { f.h.ServeHTTP(rec, req); close(done) }()
			<-started
			if cancelled {
				cancel()
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("headerless stream read did not stop")
			}
			want := 504
			if cancelled {
				want = 499
			}
			if report := f.report(t); report.Status != want {
				t.Fatal("wrong status", report.Status, want)
			}
		})
	}
}

func TestMissingContentTypeDoesNotFailOverAfterVisibleEvent(t *testing.T) {
	var calls atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) > 1 {
			missingTypeSSE(w, completed)
			return
		}
		missingTypeSSE(w, "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"first-response\"}}\n\nevent: error\ndata: {\"type\":\"error\",\"code\":\"rate_limit_exceeded\"}\n\n")
	}, account(1, "OAUTH"), account(2, "OAUTH"))
	rec := f.call("POST", "/v1/responses", `{"model":"gpt-test","input":[],"stream":true}`)
	if rec.Code != 200 || calls.Load() != 1 || !strings.Contains(rec.Body.String(), "first-response") || strings.Contains(rec.Body.String(), "resp_done") {
		t.Fatal("headerless stream mixed attempts", rec.Code, calls.Load(), rec.Body.String())
	}
	if f.report(t).Status != 429 {
		t.Fatal("wrong error report")
	}
}

func TestExplicitNonSSEContentTypeIsNotSniffed(t *testing.T) {
	h := NewHandler(&config.Config{}, nil)
	for _, contentType := range []string{"application/json", "text/html; charset=utf-8", "application/octet-stream"} {
		resp := &http.Response{Header: http.Header{"Content-Type": []string{contentType}}, Body: io.NopCloser(strings.NewReader(completed))}
		head := false
		got, err := h.responseIsSSE(context.Background(), httptest.NewRecorder(), resp, false, &head)
		if err != nil || got || head {
			t.Fatal("explicit content type was overridden", contentType, got, err)
		}
		remaining, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || string(remaining) != completed {
			t.Fatal("explicit content type read body unexpectedly")
		}
	}
}
