package proxy

import (
	"encoding/json"
	"io"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// parseRelayed splits what the client actually received into SSE events and fails the test on any
// `data:` line that is not valid JSON — which is exactly what a client-side SSE reader does.
func parseRelayed(t *testing.T, body string) []string {
	t.Helper()
	var types []string
	for _, block := range strings.Split(body, "\n\n") {
		if strings.TrimSpace(block) == "" {
			continue
		}
		var data []string
		for _, line := range strings.Split(block, "\n") {
			if strings.HasPrefix(line, "data:") {
				data = append(data, strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
			}
		}
		if len(data) == 0 {
			continue // comment-only block (a keep-alive)
		}
		joined := strings.Join(data, "\n")
		var probe struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal([]byte(joined), &probe); err != nil {
			t.Errorf("client received an unparseable SSE frame: %q (%v)", block, err)
			continue
		}
		types = append(types, probe.Type)
	}
	return types
}

// Upstream chunk boundaries are arbitrary — over HTTP/2 the body reader hands us whatever the DATA
// frames buffered, so a read routinely ends in the middle of an event. If the keep-alive timer
// fires in that window, the comment lands inside a `data:` line: its blank line terminates the
// event early and the client parses a truncated JSON payload. One 15s tick is enough to kill a
// stream that was otherwise perfectly healthy.
func TestRelaySSEDoesNotInjectKeepAliveInsideAFrame(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: content_block_delta\n" +
			`data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello`))
		// Upstream pauses mid-frame; the keep-alive timer fires here.
		time.Sleep(40 * time.Millisecond)
		_, _ = pw.Write([]byte(` world"}}` + "\n\n" + "event: message_stop\n" + `data: {"type":"message_stop"}` + "\n\n"))
		_ = pw.Close()
	}()

	rec := httptest.NewRecorder()
	relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 10*time.Millisecond, 0)

	types := parseRelayed(t, rec.Body.String())
	if len(types) != 2 || types[0] != "content_block_delta" || types[1] != "message_stop" {
		t.Errorf("relayed events = %v, want [content_block_delta message_stop]", types)
	}
}

// Same hazard, worse payload: the watchdog's error frame is written straight after a half-delivered
// event, so the client gets a mangled delta *and* a mangled error.
func TestRelaySSEStallFrameLandsOnAFrameBoundary(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: content_block_delta\n" +
			`data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"half`))
		select {} // upstream dies mid-frame and holds the socket
	}()
	defer pw.Close()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 10*time.Millisecond, 30*time.Millisecond)

	if res.status != stallStatus {
		t.Fatalf("status = %d, want %d", res.status, stallStatus)
	}
	types := parseRelayed(t, rec.Body.String())
	if len(types) == 0 || types[len(types)-1] != "error" {
		t.Errorf("relayed events = %v, want the stream to end with a parseable error frame", types)
	}
}

// A fragment upstream never finishes must not make the relay go byte-silent: the client's own byte
// watchdog would then end the request with nothing to act on. After one keep-alive period the
// fragment is written off and the comments resume.
func TestRelaySSEResumesKeepAlivesAfterAStuckFragment(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"text\":\"stu"))
		time.Sleep(150 * time.Millisecond)
		_ = pw.Close()
	}()

	rec := httptest.NewRecorder()
	// Watchdog off, so the grace is 8 keep-alive periods (40ms here) and the relay ends on EOF.
	relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 5*time.Millisecond, 0)

	body := rec.Body.String()
	if n := strings.Count(body, ": keep-alive\n\n"); n < 2 {
		t.Errorf("only %d keep-alives — the relay went silent behind a stuck fragment: %q", n, body)
	}
	parseRelayed(t, body)
}
