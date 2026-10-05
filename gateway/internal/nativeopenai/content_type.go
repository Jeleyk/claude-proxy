package nativeopenai

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

const maxSSESniffBytes = 4096

var errSSESniffTimeout = errors.New("upstream SSE prefix timed out")

type bufferedBody struct {
	*bufio.Reader
	io.Closer
}

// Codex can omit Content-Type on a successful SSE response. Probe only the missing-header
// case and retain every byte in the reader for the normal framed/JSON-validating relay.
func (h *Handler) responseIsSSE(ctx context.Context, w http.ResponseWriter, resp *http.Response, stream bool, head *bool) (bool, error) {
	contentType, _, _ := strings.Cut(strings.ToLower(resp.Header.Get("Content-Type")), ";")
	contentType = strings.TrimSpace(contentType)
	if contentType == "text/event-stream" {
		return true, nil
	}
	if contentType != "" {
		return false, nil
	}
	reader := bufio.NewReaderSize(resp.Body, maxSSESniffBytes)
	resp.Body = &bufferedBody{Reader: reader, Closer: resp.Body}
	type result struct {
		sse bool
		err error
	}
	done := make(chan result, 1)
	go func() { sse, err := sniffSSEPrefix(reader); done <- result{sse, err} }()
	timeout := h.cfg.UpstreamStallTimeout
	if timeout <= 0 {
		timeout = 120 * time.Second
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	keepAlive := time.NewTicker(15 * time.Second)
	defer keepAlive.Stop()
	for {
		select {
		case result := <-done:
			return result.sse, result.err
		case <-ctx.Done():
			return false, ctx.Err()
		case <-timer.C:
			return false, errSSESniffTimeout
		case <-keepAlive.C:
			if stream {
				startStream(w, head)
				if _, err := io.WriteString(w, ": keep-alive\n\n"); err != nil {
					return false, err
				}
				flush(w)
			}
		}
	}
}

func sniffSSEPrefix(reader *bufio.Reader) (bool, error) {
	for offset := 0; offset < maxSSESniffBytes; offset++ {
		prefix, err := reader.Peek(offset + 1)
		if err != nil {
			if errors.Is(err, io.EOF) {
				return false, nil
			}
			return false, err
		}
		switch prefix[offset] {
		case '\n', '\r':
			continue
		case ':':
			return true, nil // an SSE comment; relay still requires valid response events
		}
		field := ""
		switch prefix[offset] {
		case 'e':
			field = "event:"
		case 'd':
			field = "data:"
		case 'i':
			field = "id:"
		case 'r':
			field = "retry:"
		default:
			return false, nil
		}
		if offset+len(field) > maxSSESniffBytes {
			return false, nil
		}
		prefix, err = reader.Peek(offset + len(field))
		if err != nil {
			if errors.Is(err, io.EOF) {
				return false, nil
			}
			return false, err
		}
		return string(prefix[offset:]) == field, nil
	}
	return false, nil
}
