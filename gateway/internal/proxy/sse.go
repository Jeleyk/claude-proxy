package proxy

import (
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"claudeproxy/gateway/internal/anthropic"
)

// keepAliveInterval bounds how long the relay waits during upstream silence before emitting an
// SSE keep-alive comment. Adaptive-thinking Opus on a 1M context can stay silent 30s+ before the
// first event; without keep-alives the client (and Cloudflare/nginx) abort before the first real
// event → nginx "upstream prematurely closed connection while reading response header" (502).
const keepAliveInterval = 15 * time.Second

// stallFrame ends a stream whose upstream went silent. Shaped as `overloaded_error` because that
// is the one mid-stream error Claude Code re-sends the request on — a stalled answer is exactly
// the case where a retry is the right move.
const stallFrame = "event: error\n" +
	`data: {"type":"error","error":{"type":"overloaded_error","message":"claude-proxy: upstream stopped sending data — retry"}}` + "\n\n"

// stallStatus is recorded for a stalled attempt. Deliberately not 429: parking the account on a
// window reset because a stream went quiet would take a healthy account out of the pool for hours.
const stallStatus = 504

// pingOnly reports whether a chunk carries nothing but SSE comments and Anthropic ping frames —
// upstream noise that says the socket is alive but the answer is not moving.
func pingOnly(b []byte) bool {
	seenPing := false
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		switch {
		case line == "" || strings.HasPrefix(line, ":"):
			continue
		case line == "event: ping":
			seenPing = true
		case strings.HasPrefix(line, "data:") && strings.Contains(line, `"ping"`):
			seenPing = true
		default:
			return false
		}
	}
	return seenPing
}

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
	headSent, canRetry bool, onMidStreamErr func() (string, int), stallAfter time.Duration,
) sseResult {
	return relaySSEInterval(w, upstream, status, contentType, headSent, canRetry, onMidStreamErr, keepAliveInterval, stallAfter)
}

// relaySSEInterval is relaySSE with an injectable keep-alive interval (for tests).
func relaySSEInterval(
	w http.ResponseWriter, upstream io.ReadCloser, status int, contentType string,
	headSent, canRetry bool, onMidStreamErr func() (string, int), interval, stallAfter time.Duration,
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
	lastUpstream := time.Now()
	for {
		select {
		case b := <-dataCh:
			// Pings don't count as progress. Measured on a stuck stream: Anthropic kept sending
			// exactly one 39-byte ping every 30s for minutes after the answer stopped mid-word,
			// so a watchdog that looked at raw bytes — or a client that trusts the socket — waits
			// forever. They are still relayed (and still don't count as client-visible content,
			// so a swap stays invisible); they just don't reset the clock.
			if !pingOnly(b) {
				lastUpstream = time.Now()
			}
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
			if !pingOnly(b) {
				wroteData = true
			}
			if fl != nil {
				fl.Flush()
			}
		case <-ticker.C:
			// Upstream silent past the watchdog: the stream is dead, not thinking. Left alone it
			// hangs for as long as the client tolerates our keep-alives — Anthropic drops a
			// stream mid-answer often enough (a cold cache write of a very large prompt is where
			// we see it) that "wait forever" is the wrong default. Same decision as an in-stream
			// error: swap accounts while the client has seen nothing, otherwise end it honestly.
			if stallAfter > 0 && time.Since(lastUpstream) >= stallAfter {
				if !wroteData && canRetry {
					log.Printf("upstream silent for %s before any content — trying the next account", stallAfter)
					return sseResult{usage: scan.Usage(), mcp: scan.mcp.calls, status: recorded, retry: true}
				}
				log.Printf("upstream silent for %s mid-answer — ending the stream with a retryable error", stallAfter)
				_, _ = io.WriteString(w, stallFrame)
				if fl != nil {
					fl.Flush()
				}
				return sseResult{usage: scan.Usage(), mcp: scan.mcp.calls, status: stallStatus}
			}
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
