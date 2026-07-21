package proxy

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"claudeproxy/gateway/internal/control"
)

// forwardResult is the outcome of a single upstream attempt.
type forwardResult struct {
	retry  bool
	report control.UsageReport
}

// Hop-by-hop / auth / identity headers we never forward upstream verbatim. `x-client-id` is a
// proxy tell (real Claude Code omits it); the session-id header is re-emitted rotated in B5.
var stripRequestHeaders = map[string]bool{
	"host": true, "content-length": true, "transfer-encoding": true, "connection": true,
	"authorization": true, "x-api-key": true, "accept-encoding": true,
	"x-client-id": true, "x-claude-code-session-id": true,
}

// Statuses that make a non-last attempt retry the next account instead of passing through.
var retryableStatuses = map[int]bool{429: true, 401: true, 500: true, 502: true, 503: true, 529: true}

// forward performs one upstream attempt against a candidate. On a retryable status with
// canRetry it drains the body and returns {retry:true}; otherwise it relays the response
// (SSE or buffered JSON) to the client and returns {retry:false}.
func (h *Handler) forward(
	ctx context.Context, w http.ResponseWriter, r *http.Request,
	cand control.Candidate, cands []control.Candidate, idx int, userID, tokenID *int,
	body []byte, canRetry bool,
) forwardResult {
	outBody, sessionID := rewriteBody(body, cand.DeviceID, r.Header.Get("X-Claude-Code-Session-Id"))

	url := h.cfg.UpstreamBaseURL + r.URL.RequestURI()
	req, err := http.NewRequestWithContext(ctx, r.Method, url, strings.NewReader(string(outBody)))
	if err != nil {
		return forwardResult{retry: canRetry, report: control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: 0}}
	}

	// Copy client headers except the strip-set and telemetry headers.
	for name, vals := range r.Header {
		ln := strings.ToLower(name)
		if stripRequestHeaders[ln] || isTelemetryHeader(ln) {
			continue
		}
		for _, v := range vals {
			req.Header.Add(name, v)
		}
	}
	// Re-emit the session-id header with this account's rotated value.
	if sessionID != "" {
		req.Header.Set("X-Claude-Code-Session-Id", sessionID)
	}
	// Per-account upstream credentials (already decrypted by the service). Swap credential
	// headers (Authorization / x-api-key) outright, but MERGE anthropic-beta into whatever the
	// client sent — overwriting it would drop client betas (e.g. context-management-*), which
	// then makes the matching body field a "400 extra inputs" error. Mirrors UpstreamAuth.apply.
	for k, v := range cand.AuthHeaders {
		if strings.EqualFold(k, "anthropic-beta") {
			req.Header.Set("anthropic-beta", mergeBeta(req.Header.Values("anthropic-beta"), v))
		} else {
			req.Header.Set(k, v)
		}
	}
	// Anthropic requires this header; inject a default if the client omitted it.
	if r.Header.Get("anthropic-version") == "" {
		req.Header.Set("anthropic-version", "2023-06-01")
	}
	if len(outBody) == 0 {
		req.Body = http.NoBody
		req.ContentLength = 0
	} else {
		req.ContentLength = int64(len(outBody))
	}

	resp, err := h.upstream.Do(req)
	if err != nil {
		if canRetry {
			return forwardResult{retry: true, report: control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: 0}}
		}
		writeProxyError(w, http.StatusBadGateway, "api_error", "upstream request failed")
		return forwardResult{retry: false, report: control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: http.StatusBadGateway}}
	}
	defer resp.Body.Close()

	rlHeaders := extractRateLimitHeaders(resp.Header)
	report := control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: resp.StatusCode, RatelimitHeaders: rlHeaders}

	// Retryable status on a non-last attempt: drain and let the caller try the next account.
	if retryableStatuses[resp.StatusCode] && canRetry {
		_, _ = io.Copy(io.Discard, resp.Body)
		return forwardResult{retry: true, report: report}
	}

	// Pass the real upstream response through to the client.
	contentType := resp.Header.Get("Content-Type")
	copyResponseHeaders(w, resp.Header)

	if strings.Contains(strings.ToLower(contentType), "text/event-stream") {
		model := modelFromRequest(outBody)
		scan, mcpCalls, recorded := relaySSE(w, resp.Body, resp.StatusCode, contentType, func() (string, int) {
			return midStreamError(cands, idx)
		})
		report.Status = recorded
		report.Input, report.Output = scan.Input, scan.Output
		report.CacheRead, report.CacheWrite = scan.CacheRead, scan.CacheWrite
		report.Model = model
		report.McpCalls = mcpCalls
		return forwardResult{retry: false, report: report}
	}

	// Buffer the (single) JSON message so we can extract token usage.
	buf, _ := io.ReadAll(resp.Body)
	fillUsageFromJSON(&report, buf)
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(buf)
	return forwardResult{retry: false, report: report}
}

// mergeBeta merges the account's anthropic-beta token(s) into the client's existing
// anthropic-beta values, preserving the client's betas (e.g. context-management-*) and
// de-duplicating. Order: client betas first, then any account betas not already present.
func mergeBeta(clientVals []string, accountBeta string) string {
	seen := map[string]bool{}
	var out []string
	add := func(csv string) {
		for _, tok := range strings.Split(csv, ",") {
			t := strings.TrimSpace(tok)
			if t == "" || seen[t] {
				continue
			}
			seen[t] = true
			out = append(out, t)
		}
	}
	for _, v := range clientVals {
		add(v)
	}
	add(accountBeta)
	return strings.Join(out, ",")
}

// copyResponseHeaders copies safe upstream headers to the client, dropping framing headers the
// Go server manages itself.
func copyResponseHeaders(w http.ResponseWriter, src http.Header) {
	for name, vals := range src {
		ln := strings.ToLower(name)
		if ln == "content-length" || ln == "transfer-encoding" || ln == "connection" || ln == "content-encoding" {
			continue
		}
		for _, v := range vals {
			w.Header().Add(name, v)
		}
	}
}

// extractRateLimitHeaders keeps only the headers the service's RateLimitHeaders parser reads.
func extractRateLimitHeaders(h http.Header) map[string]string {
	out := make(map[string]string)
	for name, vals := range h {
		ln := strings.ToLower(name)
		if strings.HasPrefix(ln, "anthropic-ratelimit") || ln == "retry-after" {
			if len(vals) > 0 {
				out[ln] = vals[len(vals)-1]
			}
		}
	}
	return out
}

// fillUsageFromJSON pulls model + token counts + MCP tool-call counts out of a buffered JSON
// response.
func fillUsageFromJSON(report *control.UsageReport, body []byte) {
	var obj struct {
		Model   string `json:"model"`
		Content []struct {
			Type string `json:"type"`
			Name string `json:"name"`
		} `json:"content"`
		Usage struct {
			Input       int64 `json:"input_tokens"`
			Output      int64 `json:"output_tokens"`
			CacheRead   int64 `json:"cache_read_input_tokens"`
			CacheCreate int64 `json:"cache_creation_input_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(body, &obj); err != nil {
		return
	}
	if obj.Model != "" {
		report.Model = &obj.Model
	}
	report.Input = obj.Usage.Input
	report.Output = obj.Usage.Output
	report.CacheRead = obj.Usage.CacheRead
	report.CacheWrite = obj.Usage.CacheCreate
	var mcp mcpScan
	for _, block := range obj.Content {
		mcp.count(block.Type, block.Name)
	}
	report.McpCalls = mcp.calls
}

// modelFromRequest reads the "model" field from the request body.
func modelFromRequest(body []byte) *string {
	var obj struct {
		Model string `json:"model"`
	}
	if err := json.Unmarshal(body, &obj); err != nil || obj.Model == "" {
		return nil
	}
	return &obj.Model
}

// writeProxyError emits an Anthropic-shaped error JSON with the given HTTP status.
func writeProxyError(w http.ResponseWriter, status int, errType, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	body, _ := json.Marshal(map[string]any{"error": map[string]string{"type": errType, "message": message}})
	_, _ = w.Write(body)
}
