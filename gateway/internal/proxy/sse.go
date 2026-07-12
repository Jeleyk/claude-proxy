package proxy

import (
	"io"
	"net/http"
)

// usageScan accumulates token counts scanned out of an SSE stream. The full scanner (matching
// the Kotlin SseUsageScanner) is implemented in usagescan.go (Task B4).
type usageScan struct {
	Input, Output, CacheRead, CacheWrite int64
}

// relaySSE streams an SSE response to the client. The head is flushed immediately (the 502-
// critical behavior) and each chunk is flushed as it arrives. Keep-alives during upstream
// silence, mid-stream error injection, and usage scanning are added in Task B4.
func relaySSE(
	w http.ResponseWriter, upstream io.ReadCloser, status int, contentType string,
	onMidStreamErr func() (string, int),
) (usageScan, int) {
	fl, _ := w.(http.Flusher)
	if contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	w.WriteHeader(status)
	_, _ = io.WriteString(w, ": keep-alive\n\n") // head out immediately
	if fl != nil {
		fl.Flush()
	}

	var scan usageScan
	buf := make([]byte, 16*1024)
	for {
		n, err := upstream.Read(buf)
		if n > 0 {
			_, _ = w.Write(buf[:n])
			if fl != nil {
				fl.Flush()
			}
		}
		if err != nil {
			break
		}
	}
	return scan, status
}
