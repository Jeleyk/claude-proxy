package proxy

import (
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

// Anthropic sometimes accepts a request with a 200 and only then fails it in-stream with
// `overloaded_error`. As long as nothing client-visible has been written, another account should
// take the request over on the same open stream.
const overloadFrame = "event: error\n" +
	`data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}` + "\n\n"

func TestRelaySSERetriesWhenOverloadArrivesBeforeAnyContent(t *testing.T) {
	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, io.NopCloser(strings.NewReader(overloadFrame)), 200, "text/event-stream",
		false, true, func() (string, int) {
			t.Error("onMidStreamErr must not run when the request can still be retried")
			return "", 0
		}, time.Hour, 0)

	if !res.retry {
		t.Fatal("retry = false, want true (no content had reached the client yet)")
	}
	body := rec.Body.String()
	if strings.Contains(body, "overloaded_error") {
		t.Errorf("raw upstream error leaked to the client: %q", body)
	}
	// Only the head keep-alive comment, which SSE consumers discard.
	if body != ": keep-alive\n\n" {
		t.Errorf("client saw more than the head keep-alive: %q", body)
	}
}

func TestRelaySSEDoesNotRetryOnceContentIsOut(t *testing.T) {
	// Separate chunks: the first is relayed to the client before the overload shows up, which is
	// the point of no return for a transparent swap.
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: message_start\n" + `data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}` + "\n\n"))
		time.Sleep(10 * time.Millisecond)
		_, _ = pw.Write([]byte(overloadFrame))
		_ = pw.Close()
	}()
	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream",
		false, true, func() (string, int) { return "event: error\ndata: normalized\n\n", 529 }, time.Hour, 0)

	// Swapping accounts here would replay content the client already has.
	if res.retry {
		t.Error("retry = true, want false (content already streamed)")
	}
	if !strings.Contains(rec.Body.String(), "normalized") {
		t.Errorf("client should get the normalized error frame: %q", rec.Body.String())
	}
}

// Upstream may pack the opening event and the overload into one TCP chunk. Since the chunk is
// inspected before it is relayed, the client has still seen nothing — so the swap stays available.
// (Prompt tokens from that chunk are still reported: the account really did process them.)
func TestRelaySSERetriesWhenContentAndOverloadShareAChunk(t *testing.T) {
	data := "event: message_start\n" + `data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}` + "\n\n" +
		overloadFrame
	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, io.NopCloser(strings.NewReader(data)), 200, "text/event-stream",
		false, true, func() (string, int) { return "", 0 }, time.Hour, 0)

	if !res.retry {
		t.Error("retry = false, want true (nothing was relayed to the client)")
	}
	if strings.Contains(rec.Body.String(), "message_start") {
		t.Errorf("the failed attempt's content must not reach the client: %q", rec.Body.String())
	}
	if res.usage.Input != 7 {
		t.Errorf("usage.Input = %d, want 7 — the prompt was still processed upstream", res.usage.Input)
	}
}

func TestRelaySSEDoesNotRetryOnTheLastCandidate(t *testing.T) {
	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, io.NopCloser(strings.NewReader(overloadFrame)), 200, "text/event-stream",
		false, false, func() (string, int) { return "event: error\ndata: normalized\n\n", 429 }, time.Hour, 0)

	if res.retry {
		t.Error("retry = true, want false (no candidate left to hand it to)")
	}
	if res.status != 429 {
		t.Errorf("status = %d, want 429", res.status)
	}
	if !strings.Contains(rec.Body.String(), "normalized") {
		t.Errorf("error must be passed through to the client: %q", rec.Body.String())
	}
}

func TestRelaySSESkipsHeadWhenAlreadySent(t *testing.T) {
	data := "event: message_start\ndata: {}\n\n"
	rec := httptest.NewRecorder()
	relaySSEInterval(rec, io.NopCloser(strings.NewReader(data)), 200, "text/event-stream",
		true, false, func() (string, int) { return "", 0 }, time.Hour, 0)

	// A second head would corrupt the stream the first attempt opened.
	if strings.HasPrefix(rec.Body.String(), ": keep-alive") {
		t.Errorf("head keep-alive re-sent on a continued stream: %q", rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "message_start") {
		t.Errorf("content not relayed: %q", rec.Body.String())
	}
}

// End-to-end: first account streams a 200 that immediately overloads, second account serves the
// real answer. The client must see one clean stream and no error.
func TestHandlerSwapsAccountOnInStreamOverload(t *testing.T) {
	var calls int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		if atomic.AddInt32(&calls, 1) == 1 {
			_, _ = io.WriteString(w, overloadFrame)
			return
		}
		_, _ = io.WriteString(w, "event: message_start\n"+
			`data: {"type":"message_start","message":{"model":"m","usage":{"input_tokens":3,"output_tokens":4}}}`+"\n\n"+
			"event: content_block_delta\ndata: {\"served_by\":\"second\"}\n\n")
	}))
	defer upstream.Close()

	control_ := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/internal/resolve":
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprint(w, `{"userId":1,"overLimit":false,"candidates":[
				{"accountId":1,"type":"OAUTH","authHeaders":{}},
				{"accountId":2,"type":"OAUTH","authHeaders":{}}]}`)
		default:
			w.WriteHeader(204)
		}
	}))
	defer control_.Close()

	h := NewHandler(testConfig(upstream.URL), testControl(control_.URL))
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{"stream":true}`))
	req.Header.Set("Authorization", "Bearer cxp_token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("upstream calls = %d, want 2 (the overload should have moved to account 2)", calls)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "served_by") {
		t.Errorf("client did not receive the second account's stream: %q", body)
	}
	if strings.Contains(body, "overloaded_error") {
		t.Errorf("upstream overload leaked to the client: %q", body)
	}
	// One stream, one head: the swap must not restart the SSE preamble.
	if n := strings.Count(body, ": keep-alive\n\n"); n != 1 {
		t.Errorf("keep-alive head count = %d, want 1", n)
	}
}

// Small constructors so the tests above read as the scenario, not as wiring.
func testConfig(upstreamURL string) *config.Config {
	return &config.Config{UpstreamBaseURL: upstreamURL}
}
func testControl(serviceURL string) *control.Client { return control.New(serviceURL, "tok") }
