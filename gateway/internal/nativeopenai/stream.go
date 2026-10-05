package nativeopenai

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"claudeproxy/gateway/internal/control"
)

// do emits only comments while waiting for headers; a later attempt may still take over.
func (h *Handler) do(ctx context.Context, w http.ResponseWriter, req *http.Request, stream bool, head *bool) (*http.Response, error) {
	if !stream {
		return h.upstream.Do(req)
	}
	type result struct {
		resp *http.Response
		err  error
	}
	done := make(chan result)
	go func() {
		resp, err := h.upstream.Do(req)
		select {
		case done <- result{resp, err}:
		case <-ctx.Done():
			if resp != nil {
				resp.Body.Close()
			}
		}
	}()
	interval := h.cfg.EarlyHeadTimeout
	if interval <= 0 {
		interval = 45 * time.Second
	}
	timer := time.NewTimer(interval)
	defer timer.Stop()
	for {
		select {
		case result := <-done:
			return result.resp, result.err
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-timer.C:
			startStream(w, head)
			if _, err := io.WriteString(w, ": keep-alive\n\n"); err != nil {
				return nil, err
			}
			flush(w)
			timer.Reset(15 * time.Second)
		}
	}
}

type event struct {
	raw  []byte
	data []byte
	err  error
}

const maxEvent = 4 << 20

// Frames are bounded and complete before injection. In particular, a large or truncated
// upstream frame is never written as half a JSON object followed by a proxy error.
func events(ctx context.Context, r io.Reader) <-chan event {
	out := make(chan event)
	go func() {
		defer close(out)
		scanner := bufio.NewScanner(r)
		scanner.Buffer(make([]byte, 32<<10), maxEvent)
		var raw, data []byte
		send := func(e event) bool {
			select {
			case out <- e:
				return true
			case <-ctx.Done():
				return false
			}
		}
		for scanner.Scan() {
			line := scanner.Bytes()
			raw = append(raw, line...)
			raw = append(raw, '\n')
			if len(raw) > maxEvent {
				send(event{err: errors.New("SSE frame exceeds limit")})
				return
			}
			if len(line) == 0 {
				if len(raw) > 1 && !send(event{raw: raw, data: bytes.TrimSuffix(data, []byte{'\n'})}) {
					return
				}
				raw = nil
				data = nil
				continue
			}
			if bytes.HasPrefix(line, []byte("data:")) {
				v := bytes.TrimPrefix(line, []byte("data:"))
				v = bytes.TrimPrefix(v, []byte{' '})
				data = append(data, v...)
				data = append(data, '\n')
			}
		}
		err := scanner.Err()
		if err == nil && len(raw) > 0 {
			err = io.ErrUnexpectedEOF
		}
		if err != nil {
			send(event{err: err})
		}
	}()
	return out
}

func (h *Handler) relay(ctx context.Context, w http.ResponseWriter, resp *http.Response, p prepared, canRetry bool, head, delivered *bool, report *control.UsageReport) bool {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	frames := events(ctx, resp.Body)
	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()
	stall := h.cfg.UpstreamStallTimeout
	if stall <= 0 {
		stall = 120 * time.Second
	}
	deadline := time.NewTimer(stall)
	defer deadline.Stop()
	visible := false
	var output outputCollector
	failure := func(status int, msg string) bool {
		report.Status = status
		if canRetry && !visible && ctx.Err() == nil {
			return true
		}
		if ctx.Err() == nil {
			fail(w, head, p.stream, status, msg)
		}
		return false
	}
	for {
		select {
		case <-ctx.Done():
			report.Status = 499
			return false
		case <-deadline.C:
			return failure(504, "OpenAI stream stalled")
		case <-heartbeat.C:
			if p.stream {
				startStream(w, head)
				if _, err := io.WriteString(w, ": keep-alive\n\n"); err != nil {
					report.Status = 499
					return false
				}
				flush(w)
			}
		case frame, ok := <-frames:
			if !ok {
				return failure(502, "OpenAI stream ended before a terminal response")
			}
			if frame.err != nil {
				return failure(502, "Invalid OpenAI event stream")
			}
			if len(frame.data) == 0 {
				continue
			}
			if bytes.Equal(frame.data, []byte("[DONE]")) {
				return failure(502, "OpenAI stream ended without a completed response")
			}
			var e struct {
				Type        string          `json:"type"`
				Response    json.RawMessage `json:"response"`
				Item        json.RawMessage `json:"item"`
				OutputIndex *int            `json:"output_index"`
				Code        string          `json:"code"`
				Error       struct {
					Code string `json:"code"`
				} `json:"error"`
			}
			if json.Unmarshal(frame.data, &e) != nil {
				return failure(502, "Invalid OpenAI event JSON")
			}
			if !deadline.Stop() {
				select {
				case <-deadline.C:
				default:
				}
			}
			deadline.Reset(stall)
			scanRateEvent(frame.data, report)
			if !p.stream && e.Type == "response.output_item.done" {
				if err := output.add(e.OutputIndex, e.Item); err != nil {
					return failure(502, "OpenAI buffered output exceeds limits or is invalid")
				}
			}
			terminal := e.Type == "response.completed" || e.Type == "response.incomplete"
			failed := e.Type == "error" || e.Type == "response.failed"
			if terminal && !validResponse(e.Response) {
				return failure(502, "Invalid terminal OpenAI response")
			}
			if len(e.Response) > 0 {
				scanResponse(e.Response, report)
			}
			if failed {
				report.Status = streamErrorStatus(e.Code, e.Error.Code, e.Response)
				if canRetry && !visible && retryable(report.Status) {
					return true
				}
				// Error messages can contain upstream account metadata; return a stable sanitized error.
				if !*head {
					copyRateHeaders(w.Header(), resp.Header)
				}
				fail(w, head, p.stream, report.Status, "OpenAI response failed")
				return false
			}
			if p.stream {
				if !*head {
					copyRateHeaders(w.Header(), resp.Header)
				}
				startStream(w, head)
				if strings.HasPrefix(e.Type, "response.") {
					*delivered = true
				}
				if _, err := w.Write(frame.raw); err != nil {
					report.Status = 499
					return false
				}
				flush(w)
				visible = true
			}
			if terminal || failed {
				if !p.stream {
					data, err := output.complete(e.Response)
					if err != nil {
						return failure(502, "OpenAI buffered response exceeds limits or is invalid")
					}
					copyRateHeaders(w.Header(), resp.Header)
					writeDeadline(w)
					w.Header().Set("Content-Type", "application/json")
					w.WriteHeader(200)
					*delivered = true
					_, _ = w.Write(data)
				}
				return false
			}
		}
	}
}

func streamErrorStatus(codes ...any) int {
	code := ""
	for _, v := range codes {
		switch x := v.(type) {
		case string:
			code += " " + x
		case json.RawMessage:
			var response struct {
				Error struct {
					Code string `json:"code"`
				} `json:"error"`
			}
			_ = json.Unmarshal(x, &response)
			code += " " + response.Error.Code
		}
	}
	if strings.Contains(code, "usage_limit") || strings.Contains(code, "rate_limit") {
		return 429
	}
	if strings.Contains(code, "invalid_api_key") || strings.Contains(code, "authentication") {
		return 401
	}
	if strings.Contains(code, "invalid_request") || strings.Contains(code, "context_length") {
		return 400
	}
	return 502
}

func scanResponse(data []byte, report *control.UsageReport) {
	var response struct {
		Model string `json:"model"`
		Usage *struct {
			Input   int64 `json:"input_tokens"`
			Output  int64 `json:"output_tokens"`
			Details struct {
				Cached int64 `json:"cached_tokens"`
			} `json:"input_tokens_details"`
		} `json:"usage"`
	}
	if json.Unmarshal(data, &response) != nil {
		return
	}
	if report.Model == nil && response.Model != "" {
		report.Model = &response.Model
	}
	if response.Usage == nil {
		return
	}
	u := response.Usage
	// OpenAI input_tokens includes cached tokens; this service prices the two buckets separately.
	cached := max(int64(0), min(u.Details.Cached, u.Input))
	report.Input = max(int64(0), u.Input-cached)
	report.CacheRead = cached
	report.Output = max(int64(0), u.Output)
	// Output already includes reasoning_tokens. Never bill that detail a second time.
}

func scanRateEvent(data []byte, report *control.UsageReport) {
	type window struct {
		Used    *float64 `json:"used_percent"`
		Minutes *int64   `json:"window_minutes"`
		Reset   *int64   `json:"reset_at"`
	}
	var e struct {
		Type             string  `json:"type"`
		MeteredLimitName *string `json:"metered_limit_name"`
		LimitName        *string `json:"limit_name"`
		Limits           struct {
			Primary   *window `json:"primary"`
			Secondary *window `json:"secondary"`
		} `json:"rate_limits"`
	}
	if json.Unmarshal(data, &e) != nil || e.Type != "codex.rate_limits" {
		return
	}
	// Named/model-specific buckets are not the shared account quota. Until the service can
	// store independent buckets, preserve the event for the client without flattening it.
	name := e.MeteredLimitName
	if name == nil {
		name = e.LimitName
	}
	if name != nil && strings.ReplaceAll(strings.ToLower(strings.TrimSpace(*name)), "-", "_") != "codex" {
		return
	}
	if report.RatelimitHeaders == nil {
		report.RatelimitHeaders = map[string]string{}
	}
	for name, w := range map[string]*window{"primary": e.Limits.Primary, "secondary": e.Limits.Secondary} {
		if w == nil {
			continue
		}
		prefix := "x-codex-" + name
		if w.Used != nil {
			report.RatelimitHeaders[prefix+"-used-percent"] = strconv.FormatFloat(*w.Used, 'f', -1, 64)
		}
		if w.Minutes != nil {
			report.RatelimitHeaders[prefix+"-window-minutes"] = strconv.FormatInt(*w.Minutes, 10)
		}
		if w.Reset != nil {
			report.RatelimitHeaders[prefix+"-reset-at"] = strconv.FormatInt(*w.Reset, 10)
		}
	}
}
