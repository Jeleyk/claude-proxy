package proxy

import (
	"io"
	"net/http"
	"time"

	"claudeproxy/gateway/internal/anthropic"
)

// keepAliveInterval bounds how long the relay waits during upstream silence before emitting an
// SSE keep-alive comment. Adaptive-thinking Opus on a 1M context can stay silent 30s+ before the
// first event; without keep-alives the client (and Cloudflare/nginx) abort before the first real
// event → nginx "upstream prematurely closed connection while reading response header" (502).
const keepAliveInterval = 15 * time.Second

// sseResult is the outcome of relaying one SSE response.
type sseResult struct {
	usage  anthropic.Usage
	mcp    map[string]int64
	status int
	// retry means the upstream failed with a retryable error (overloaded / rate-limited) before
	// the client had seen a single byte of real content, so the next account can take over
	// invisibly — see relaySSEInterval.
	retry bool
}

// relaySSE streams an SSE response to the client in real time while teeing token usage and MCP
// tool-call counts out of the stream. It flushes the response head immediately, injects
// keep-alive comments during silence, and handles a retryable mid-stream error either by
// retrying the next account (nothing client-visible written yet) or by writing a normalized
// error frame (via onMidStreamErr) so the client re-sends the request.
//
// [headSent] tells it a previous attempt already wrote the response head, so this one must not
// write it again. [canRetry] is false on the last candidate.
func relaySSE(
	w http.ResponseWriter, upstream io.ReadCloser, status int, contentType string,
	headSent, canRetry bool, onMidStreamErr func() (string, int),
) sseResult {
	return relaySSEInterval(w, upstream, status, contentType, headSent, canRetry, onMidStreamErr, keepAliveInterval)
}

// relaySSEInterval is relaySSE with an injectable keep-alive interval (for tests).
func relaySSEInterval(
	w http.ResponseWriter, upstream io.ReadCloser, status int, contentType string,
	headSent, canRetry bool, onMidStreamErr func() (string, int), interval time.Duration,
) sseResult {
	fl, _ := w.(http.Flusher)
	if !headSent {
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
	}

	var scan streamScan
	var errs anthropic.ErrScan
	recorded := status
	// Whether any upstream bytes reached the client. Keep-alive comments don't count: SSE
	// consumers discard comment lines, so a stream that has only sent those is still a blank
	// slate another account can take over.
	wroteData := false

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
			errs.Feed(b)
			if errs.Retryable() != "" {
				// A limit/overload (typically `overloaded_error`) surfaced *inside* the stream.
				// If the client hasn't seen any real content yet, the next account can pick the
				// request up on this same open stream and nothing about the swap is observable —
				// so do that rather than failing a request another account could serve.
				if !wroteData && canRetry {
					return sseResult{usage: scan.Usage(), mcp: scan.mcp.calls, status: recorded, retry: true}
				}
				// Content is already out (or there's no account left): mid-stream is too late for
				// a transparent swap. Don't relay the raw error frame; hand the client a
				// normalized retryable error so it re-sends the whole request.
				frame, rs := onMidStreamErr()
				if frame != "" {
					_, _ = io.WriteString(w, frame)
					if fl != nil {
						fl.Flush()
					}
				}
				recorded = rs
				return sseResult{usage: scan.Usage(), mcp: scan.mcp.calls, status: recorded}
			}
			_, _ = w.Write(b)
			wroteData = true
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
			return sseResult{usage: scan.Usage(), mcp: scan.mcp.calls, status: recorded}
		}
	}
}
