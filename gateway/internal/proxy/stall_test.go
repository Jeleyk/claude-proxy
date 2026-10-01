package proxy

import (
	"io"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A stream that opens and then goes quiet forever used to hang for as long as the client
// tolerated our keep-alives — minutes of a spinner, then "Response stalled mid-stream" with a
// half-written answer. The watchdog ends it while the client can still act on the error.
func TestRelaySSEEndsAStalledStreamWithARetryableError(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: message_start\n" + `data: {"type":"message_start","message":{"usage":{"input_tokens":9}}}` + "\n\n"))
		// then silence, holding the pipe open the way a dead upstream holds the socket
		select {}
	}()
	defer pw.Close()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "event: error\ndata: normalized\n\n", 529 },
		10*time.Millisecond, 40*time.Millisecond)

	if res.status != stallStatus {
		t.Errorf("status = %d, want %d", res.status, stallStatus)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "overloaded_error") {
		t.Errorf("client got no retryable error frame: %q", body)
	}
	if !strings.Contains(body, "message_start") {
		t.Errorf("content received before the stall must still reach the client: %q", body)
	}
	if res.usage.Input != 9 {
		t.Errorf("usage.Input = %d, want 9 — the prompt was processed and must be billed", res.usage.Input)
	}
}

// Nothing client-visible has been written yet, so the next account can take the request over on
// the same open stream — exactly what an in-stream `overloaded_error` does.
func TestRelaySSERetriesNextAccountWhenUpstreamStallsBeforeContent(t *testing.T) {
	pr, pw := io.Pipe()
	defer pw.Close()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, true,
		func() (string, int) { t.Error("onMidStreamErr must not run while a retry is available"); return "", 0 },
		10*time.Millisecond, 40*time.Millisecond)

	if !res.retry {
		t.Fatal("retry = false, want true")
	}
	// Keep-alive comments are all the client may have seen — SSE consumers discard them, so the
	// stream is still a blank slate for the next account.
	if body := strings.ReplaceAll(rec.Body.String(), ": keep-alive\n\n", ""); body != "" {
		t.Errorf("client saw more than keep-alive comments: %q", body)
	}
}

// A slow answer is not a stalled one: as long as bytes keep arriving, the watchdog must stay out
// of the way — adaptive-thinking Opus can trickle for a long time.
func TestRelaySSEDoesNotFireOnASlowButLiveStream(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		for i := 0; i < 6; i++ {
			_, _ = pw.Write([]byte("event: content_block_delta\ndata: {}\n\n"))
			time.Sleep(15 * time.Millisecond)
		}
		_ = pw.Close()
	}()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 5*time.Millisecond, 40*time.Millisecond)

	if res.status != 200 {
		t.Errorf("status = %d, want 200 — the stream was alive throughout", res.status)
	}
	if n := strings.Count(rec.Body.String(), "content_block_delta"); n != 6 {
		t.Errorf("relayed %d deltas, want 6", n)
	}
	if strings.Contains(rec.Body.String(), "stopped sending data") {
		t.Error("watchdog fired on a live stream")
	}
}

// The shape actually seen in production: upstream stops generating but keeps pinging every 30s.
// The socket looks perfectly healthy, so only a watchdog that ignores pings can end this.
func TestRelaySSEFiresWhileUpstreamKeepsPinging(t *testing.T) {
	ping := "event: ping\n" + `data: {"type": "ping"}` + "\n\n"
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: content_block_delta\n" + `data: {"type":"content_block_delta"}` + "\n\n"))
		for {
			time.Sleep(10 * time.Millisecond)
			if _, err := pw.Write([]byte(ping)); err != nil {
				return
			}
		}
	}()
	defer pw.Close()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 5*time.Millisecond, 60*time.Millisecond)

	if res.status != stallStatus {
		t.Errorf("status = %d, want %d — pings must not pass for progress", res.status, stallStatus)
	}
	if !strings.Contains(rec.Body.String(), "overloaded_error") {
		t.Errorf("client got no retryable error frame: %q", rec.Body.String())
	}
}

func TestPingOnly(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want bool
	}{
		{"anthropic ping frame", "event: ping\n" + `data: {"type": "ping"}` + "\n\n", true},
		{"ping without the space", "event: ping\n" + `data: {"type":"ping"}` + "\n\n", true},
		{"comment only", ": keep-alive\n\n", false},
		{"content delta", "event: content_block_delta\n" + `data: {"type":"content_block_delta"}` + "\n\n", false},
		{"ping bundled with content", "event: ping\ndata: {\"type\": \"ping\"}\n\nevent: message_stop\ndata: {}\n\n", false},
		{"empty", "", false},
	}
	for _, c := range cases {
		if got := pingOnly([]byte(c.in)); got != c.want {
			t.Errorf("%s: pingOnly = %v, want %v", c.name, got, c.want)
		}
	}
}

// The watchdog is off when the timeout is zero: silence then stays a matter for the client.
func TestRelaySSEWatchdogDisabledByZero(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		time.Sleep(60 * time.Millisecond)
		_ = pw.Close()
	}()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 10*time.Millisecond, 0)

	if res.status != 200 {
		t.Errorf("status = %d, want 200", res.status)
	}
	if strings.Contains(rec.Body.String(), "stopped sending data") {
		t.Error("watchdog fired although it is disabled")
	}
}

// The silence that broke production: the model streams a preamble, opens a `tool_use` block, and
// then Anthropic sends nothing but pings while it buffers a 40 KB parameter value. Timing alone
// cannot tell that apart from a dead stream, so the watchdog has to know about the open block —
// otherwise every long Write dies at the watchdog mark and the client's retry repeats it.
func TestRelaySSEWaitsOutABufferedToolInput(t *testing.T) {
	ping := "event: ping\n" + `data: {"type": "ping"}` + "\n\n"
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: content_block_delta\n" +
			`data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Writing the plan."}}` + "\n\n"))
		_, _ = pw.Write([]byte("event: content_block_start\n" +
			`data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"Write","input":{}}}` + "\n\n"))
		// Anthropic buffers the parameter: pings only, well past the plain watchdog budget.
		for i := 0; i < 10; i++ {
			time.Sleep(10 * time.Millisecond)
			if _, err := pw.Write([]byte(ping)); err != nil {
				return
			}
		}
		_, _ = pw.Write([]byte("event: content_block_delta\n" +
			`data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"content\":\"…\"}"}}` + "\n\n" +
			"event: content_block_stop\n" + `data: {"type":"content_block_stop","index":1}` + "\n\n" +
			"event: message_stop\n" + `data: {"type":"message_stop"}` + "\n\n"))
		_ = pw.Close()
	}()
	defer pw.Close()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 5*time.Millisecond, 50*time.Millisecond)

	if res.status != 200 {
		t.Errorf("status = %d, want 200 — a buffered tool input is not a stalled stream", res.status)
	}
	if !strings.Contains(rec.Body.String(), "message_stop") {
		t.Errorf("the answer never reached the client: %q", rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), "stopped sending data") {
		t.Error("watchdog killed a live tool-input stream")
	}
}

// The grace is bounded: once the tool block closes, ordinary silence is a stall again.
func TestRelaySSEStillFiresAfterTheToolBlockCloses(t *testing.T) {
	pr, pw := io.Pipe()
	go func() {
		_, _ = pw.Write([]byte("event: content_block_start\n" +
			`data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"Write","input":{}}}` + "\n\n" +
			"event: content_block_stop\n" + `data: {"type":"content_block_stop","index":0}` + "\n\n"))
		select {}
	}()
	defer pw.Close()

	rec := httptest.NewRecorder()
	res := relaySSEInterval(rec, pr, 200, "text/event-stream", false, false,
		func() (string, int) { return "", 0 }, 5*time.Millisecond, 30*time.Millisecond)

	if res.status != stallStatus {
		t.Errorf("status = %d, want %d", res.status, stallStatus)
	}
}
