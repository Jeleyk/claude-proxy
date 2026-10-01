package proxy

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"claudeproxy/gateway/internal/control"
)

// firstWriteWriter records the response and signals the moment the client sees its first byte,
// which is what a proxy in front of us (Cloudflare, nginx) measures its read timeout against.
type firstWriteWriter struct {
	*httptest.ResponseRecorder
	first chan struct{}
	once  sync.Once
}

func (f *firstWriteWriter) Write(b []byte) (int, error) {
	f.once.Do(func() { close(f.first) })
	return f.ResponseRecorder.Write(b)
}

// WriteString has to signal too: io.WriteString picks the io.StringWriter the embedded recorder
// promotes, and would otherwise write straight past Write.
func (f *firstWriteWriter) WriteString(s string) (int, error) {
	f.once.Do(func() { close(f.first) })
	return f.ResponseRecorder.WriteString(s)
}

func newFirstWriteWriter() *firstWriteWriter {
	return &firstWriteWriter{ResponseRecorder: httptest.NewRecorder(), first: make(chan struct{})}
}

// thinkingUpstream answers with SSE, but only once the test releases it — standing in for
// Anthropic taking its time over the response head.
func thinkingUpstream(release <-chan struct{}, body string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		_, _ = io.WriteString(w, body)
	}))
}

// The 524: Anthropic thinks (or a couple of candidates get retried) for longer than the proxy in
// front of us tolerates, and the client has not seen a single byte. The early head fixes exactly
// that — the stream opens while upstream is still silent.
func TestForwardOpensTheStreamWhileUpstreamIsStillSilent(t *testing.T) {
	release := make(chan struct{})
	releaseUpstream := sync.OnceFunc(func() { close(release) })
	upstream := thinkingUpstream(release, "event: message_stop\ndata: {}\n\n")
	defer upstream.Close()
	// Runs before Close (defers are LIFO): a failed assertion must not park the handler.
	defer releaseUpstream()

	h := testHandler(upstream.URL)
	h.cfg.EarlyHeadTimeout = 10 * time.Millisecond

	body := []byte(`{"model":"claude-opus-4-8","stream":true}`)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(string(body)))
	rec := newFirstWriteWriter()
	cand := control.Candidate{AccountID: 1, Type: "OAUTH"}

	resCh := make(chan forwardResult, 1)
	go func() {
		resCh <- h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, body, false, false)
	}()

	select {
	case <-rec.first:
		// the client is streaming while upstream has not answered yet — the point of the fix
	case <-time.After(2 * time.Second):
		t.Fatal("client saw nothing while upstream was silent — a proxy in front would time out (524)")
	}

	releaseUpstream()
	res := <-resCh

	if !res.headSent {
		t.Error("headSent = false, want true — the head went out before the upstream answer")
	}
	if b := rec.Body.String(); !strings.Contains(b, ": keep-alive") {
		t.Errorf("no keep-alive comment reached the client: %q", b)
	}
	if b := rec.Body.String(); !strings.Contains(b, "message_stop") {
		t.Errorf("the real answer must still be relayed after the early head: %q", b)
	}
}

// A client that asked for JSON cannot parse an event-stream, so the early head must stay out of
// its way — even though that means it keeps waiting in silence.
func TestForwardKeepsSilentForANonStreamingRequest(t *testing.T) {
	release := make(chan struct{})
	releaseUpstream := sync.OnceFunc(func() { close(release) })
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = io.WriteString(w, `{"model":"claude-opus-4-8","usage":{"input_tokens":5}}`)
	}))
	defer upstream.Close()
	// Runs before Close (defers are LIFO): a failed assertion must not park the handler.
	defer releaseUpstream()

	h := testHandler(upstream.URL)
	h.cfg.EarlyHeadTimeout = 10 * time.Millisecond

	body := []byte(`{"model":"claude-opus-4-8"}`)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(string(body)))
	rec := newFirstWriteWriter()
	cand := control.Candidate{AccountID: 1, Type: "OAUTH"}

	resCh := make(chan forwardResult, 1)
	go func() {
		resCh <- h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, body, false, false)
	}()

	select {
	case <-rec.first:
		t.Fatal("early head opened a stream for a client waiting on a JSON body")
	case <-time.After(60 * time.Millisecond):
	}

	releaseUpstream()
	res := <-resCh

	if res.headSent {
		t.Error("headSent = true, want false for a buffered JSON answer")
	}
	if b := rec.Body.String(); strings.Contains(b, "keep-alive") {
		t.Errorf("client got SSE comments in a JSON response: %q", b)
	}
}

// The early head must not cost the pool its transparent retry: keep-alive comments are discarded
// by SSE consumers, so an account that then answers 429 can still be swapped invisibly.
func TestForwardStillRetriesTheNextAccountAfterAnEarlyHead(t *testing.T) {
	release := make(chan struct{})
	releaseUpstream := sync.OnceFunc(func() { close(release) })
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release
		w.WriteHeader(429)
		_, _ = io.WriteString(w, `{"error":{"type":"rate_limit_error"}}`)
	}))
	defer upstream.Close()
	// Runs before Close (defers are LIFO): a failed assertion must not park the handler.
	defer releaseUpstream()

	h := testHandler(upstream.URL)
	h.cfg.EarlyHeadTimeout = 10 * time.Millisecond

	body := []byte(`{"model":"claude-opus-4-8","stream":true}`)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(string(body)))
	rec := newFirstWriteWriter()
	first := control.Candidate{AccountID: 1, Type: "OAUTH"}
	second := control.Candidate{AccountID: 2, Type: "OAUTH"}

	resCh := make(chan forwardResult, 1)
	go func() {
		resCh <- h.forward(context.Background(), rec, req, first, []control.Candidate{first, second}, 0, nil, nil, body, true, false)
	}()

	select {
	case <-rec.first:
	case <-time.After(2 * time.Second):
		t.Fatal("client saw nothing while upstream was silent")
	}

	releaseUpstream()
	res := <-resCh

	if !res.retry {
		t.Fatal("retry = false, want true — the next account must take this over")
	}
	if !res.headSent {
		t.Error("headSent = false, want true — the next attempt writes into the open stream")
	}
	if b := strings.ReplaceAll(rec.Body.String(), ": keep-alive\n\n", ""); b != "" {
		t.Errorf("client saw more than keep-alive comments before the retry: %q", b)
	}
}

// Zero turns the early head off: the request then waits in silence the way it did before.
func TestForwardEarlyHeadDisabledByZero(t *testing.T) {
	release := make(chan struct{})
	releaseUpstream := sync.OnceFunc(func() { close(release) })
	upstream := thinkingUpstream(release, "event: message_stop\ndata: {}\n\n")
	defer upstream.Close()
	// Runs before Close (defers are LIFO): a failed assertion must not park the handler.
	defer releaseUpstream()

	h := testHandler(upstream.URL) // cfg.EarlyHeadTimeout is zero
	body := []byte(`{"model":"claude-opus-4-8","stream":true}`)
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(string(body)))
	rec := newFirstWriteWriter()
	cand := control.Candidate{AccountID: 1, Type: "OAUTH"}

	resCh := make(chan forwardResult, 1)
	go func() {
		resCh <- h.forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, body, false, false)
	}()

	select {
	case <-rec.first:
		t.Fatal("head went out although the early head is disabled")
	case <-time.After(60 * time.Millisecond):
	}

	releaseUpstream()
	<-resCh
}

func TestStreamRequested(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want bool
	}{
		{"streaming", `{"model":"claude-opus-4-8","stream":true}`, true},
		{"explicitly buffered", `{"model":"claude-opus-4-8","stream":false}`, false},
		{"omitted", `{"model":"claude-opus-4-8"}`, false},
		{"empty body", "", false},
		{"not json", "nonsense", false},
	}
	for _, c := range cases {
		if got := streamRequested([]byte(c.in)); got != c.want {
			t.Errorf("%s: streamRequested = %v, want %v", c.name, got, c.want)
		}
	}
}
