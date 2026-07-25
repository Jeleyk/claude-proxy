package proxy

import (
	"io"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestRelaySSEFlushesHeadAndScansUsage(t *testing.T) {
	data := "event: message_start\n" +
		`data: {"type":"message_start","message":{"usage":{"input_tokens":1200,"output_tokens":1}}}` + "\n\n"
	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, io.NopCloser(strings.NewReader(data)), 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, time.Hour)
	scan, status := res.scan, res.status

	if status != 200 {
		t.Errorf("status = %d", status)
	}
	body := rec.Body.String()
	if !strings.HasPrefix(body, ": keep-alive\n\n") {
		t.Errorf("head keep-alive not flushed first: %q", body[:min(30, len(body))])
	}
	if !strings.Contains(body, "message_start") {
		t.Errorf("event bytes not relayed: %q", body)
	}
	if scan.Input != 1200 {
		t.Errorf("scan.Input = %d, want 1200", scan.Input)
	}
}

func TestRelaySSEEmitsKeepAlivesDuringSilence(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		time.Sleep(70 * time.Millisecond) // silent, then close with no data
		_ = pw.Close()
	}()
	rec := httptest.NewRecorder()
	relaySSEInterval(rec, pr, 200, "text/event-stream", false, false, func() (string, int) { return "", 0 }, 20*time.Millisecond)

	// Head keep-alive + at least one during the silence.
	if n := strings.Count(rec.Body.String(), ": keep-alive\n\n"); n < 2 {
		t.Errorf("keep-alive count = %d, want >= 2", n)
	}
}

func TestRelaySSEInjectsMidStreamError(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: message_start\ndata: {\"usage\":{\"input_tokens\":5}}\n\n"))
		time.Sleep(10 * time.Millisecond)
		_, _ = pw.Write([]byte(`event: error` + "\n" + `data: {"type":"error","error":{"type":"overloaded_error"}}` + "\n\n"))
		_ = pw.Close()
	}()
	rec := httptest.NewRecorder()
	called := false
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false, func() (string, int) {
		called = true
		return "event: error\ndata: injected-retry\n\n", 529
	}, time.Hour)
	scan, status := res.scan, res.status

	if !called {
		t.Fatal("onMidStreamErr not called")
	}
	if status != 529 {
		t.Errorf("recorded status = %d, want 529", status)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "injected-retry") {
		t.Errorf("injected frame missing: %q", body)
	}
	if strings.Contains(body, "overloaded_error") {
		t.Errorf("raw upstream error frame must not be relayed: %q", body)
	}
	if scan.Input != 5 {
		t.Errorf("scan.Input = %d, want 5", scan.Input)
	}
}
