package proxy

import (
	"io"
	"net/http"
	"time"
)

// keepAliveInterval bounds how long the relay waits during upstream silence before emitting an
// SSE keep-alive comment. Adaptive-thinking Opus on a 1M context can stay silent 30s+ before the
// first event; without keep-alives the client (and Cloudflare/nginx) abort before the first real
// event → nginx "upstream prematurely closed connection while reading response header" (502).
const keepAliveInterval = 15 * time.Second

// relaySSE streams an SSE response to the client in real time while teeing token usage and MCP
// tool-call counts out of the stream. It flushes the response head immediately, injects
// keep-alive comments during silence, and on a retryable mid-stream error writes a normalized
// error frame (via onMidStreamErr) so the client retries. Returns the scanned usage, the MCP
// tool-call counts (nil when none), and the status to record for this attempt.
func relaySSE(
	w http.ResponseWriter, upstream io.ReadCloser, status int, contentType string,
	onMidStreamErr func() (string, int),
) (usageScan, map[string]int64, int) {
	return relaySSEInterval(w, upstream, status, contentType, onMidStreamErr, keepAliveInterval)
}

// relaySSEInterval is relaySSE with an injectable keep-alive interval (for tests).
func relaySSEInterval(
	w http.ResponseWriter, upstream io.ReadCloser, status int, contentType string,
	onMidStreamErr func() (string, int), interval time.Duration,
) (usageScan, map[string]int64, int) {
	fl, _ := w.(http.Flusher)
	if contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	w.WriteHeader(status)
	// Send the head + an ignored SSE comment right away so the client enters streaming mode
	// immediately, even if the first real event is far off.
	_, _ = io.WriteString(w, ": keep-alive\n\n")
	if fl != nil {
		fl.Flush()
	}

	var scan usageScan
	var mcp mcpScan
	var errs errScan
	recorded := status

	// Read on a goroutine so we can select on a keep-alive timer during upstream silence.
	done := make(chan struct{})
	defer close(done)
	dataCh := make(chan []byte)
	errCh := make(chan error, 1)
	go func() {
		buf := make([]byte, 16*1024)
		for {
			n, err := upstream.Read(buf)
			if n > 0 {
				b := make([]byte, n)
				copy(b, buf[:n])
				select {
				case dataCh <- b:
				case <-done:
					return
				}
			}
			if err != nil {
				errCh <- err
				return
			}
		}
	}()

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case b := <-dataCh:
			scan.Feed(b)
			mcp.Feed(b)
			errs.Feed(b)
			if errs.retryable() != "" {
				// A limit/overload surfaced *inside* the stream: the 200 head is already out, so
				// we can't retry another account transparently. Don't relay the raw error frame;
				// hand the client a normalized retryable error so it re-sends the whole request.
				frame, rs := onMidStreamErr()
				if frame != "" {
					_, _ = io.WriteString(w, frame)
					if fl != nil {
						fl.Flush()
					}
				}
				recorded = rs
				return scan, mcp.calls, recorded
			}
			_, _ = w.Write(b)
			if fl != nil {
				fl.Flush()
			}
		case <-ticker.C:
			// Upstream silent (thinking) → keep the connection warm.
			_, _ = io.WriteString(w, ": keep-alive\n\n")
			if fl != nil {
				fl.Flush()
			}
		case <-errCh:
			// Upstream closed (EOF or error); usage already scanned.
			return scan, mcp.calls, recorded
		}
	}
}
