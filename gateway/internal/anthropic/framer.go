package anthropic

import "bytes"

// maxPendingFrame caps how much of an unterminated event the framer will hold back. Anthropic's
// largest single event (a buffered tool-input flush) is orders of magnitude below this; a buffer
// past it means the stream is not the `\n\n`-delimited SSE we think it is, and passing the bytes
// through unframed is strictly better than withholding them forever.
const maxPendingFrame = 1 << 20

// SSEFramer re-aligns an SSE byte stream onto event boundaries before it is written to the client.
//
// Upstream chunk boundaries are arbitrary — over HTTP/2 the body reader hands back whatever the
// DATA frames happened to buffer, so a read routinely ends in the middle of a `data:` line. That is
// harmless while the relay only copies bytes, but the relay also *injects* bytes of its own:
// keep-alive comments during silence, and a normalized error frame when the stream is abandoned.
// Injected into the middle of an event, a `\n\n` terminates it early and the client parses a
// truncated `data:` payload — `{"type":"content_block_delta",…,"text":"hel: keep-alive` — which
// fails JSON.parse and kills the whole response mid-answer.
//
// Feed therefore releases only whole events and holds the trailing fragment until it completes.
// Nothing is delayed that the client could have used: an SSE reader buffers a partial event exactly
// the same way.
type SSEFramer struct {
	buf []byte
}

// Feed adds a chunk and returns the prefix that is safe to write now: every complete event, and
// nothing else. The returned slice is only valid until the next call.
func (f *SSEFramer) Feed(b []byte) []byte {
	if len(f.buf) == 0 {
		// Fast path: a chunk that already ends on a boundary passes through without a copy, which
		// is the common case when upstream flushes one event per write.
		if end := lastFrameEnd(b); end == len(b) {
			return b
		}
	}
	f.buf = append(f.buf, b...)
	end := lastFrameEnd(f.buf)
	if end == 0 {
		if len(f.buf) > maxPendingFrame {
			return f.take(len(f.buf))
		}
		return nil
	}
	return f.take(end)
}

// take releases the first n buffered bytes, keeping the remainder as the pending fragment.
func (f *SSEFramer) take(n int) []byte {
	out := f.buf[:n]
	rest := f.buf[n:]
	f.buf = append([]byte(nil), rest...)
	return out
}

// Pending reports how many bytes of an unterminated event are being held back. While it is
// non-zero the relay must not inject anything of its own — the client is mid-event.
func (f *SSEFramer) Pending() int { return len(f.buf) }

// Discard drops the pending fragment. Called when the stream is being ended on our terms (a stall
// or an upstream close): a fragment that will never be completed is not deliverable, and dropping
// it is what lets the terminating error frame land on a clean boundary.
func (f *SSEFramer) Discard() { f.buf = f.buf[:0] }

// lastFrameEnd returns the offset just past the last event terminator in b, or 0 if there is none.
// Both LF and CRLF framing are recognized; Anthropic sends LF, but an SSE stream is allowed either.
func lastFrameEnd(b []byte) int {
	end := 0
	if i := bytes.LastIndex(b, []byte("\n\n")); i >= 0 {
		end = i + 2
	}
	if i := bytes.LastIndex(b, []byte("\r\n\r\n")); i >= 0 && i+4 > end {
		end = i + 4
	}
	return end
}
