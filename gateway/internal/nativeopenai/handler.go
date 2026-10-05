// Package nativeopenai serves Responses through actual OpenAI/Codex accounts. It is separate
// from openaigw, which translates Chat Completions to Anthropic and remains unchanged.
package nativeopenai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

const maxBody = 16 << 20
const maxResponse = 32 << 20

var clientVersionPattern = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$`)

type Handler struct {
	cfg      *config.Config
	ctrl     *control.Client
	upstream *http.Client
	affinity *affinityStore
}

func NewHandler(cfg *config.Config, ctrl *control.Client) *Handler {
	return &Handler{cfg: cfg, ctrl: ctrl, affinity: newAffinityStore(), upstream: &http.Client{
		// An upstream redirect must never move account credentials to another origin.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Transport: &http.Transport{DialContext: (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
			TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 60 * time.Second,
			MaxIdleConns: 100, IdleConnTimeout: 90 * time.Second, ForceAttemptHTTP2: true},
	}}
}

type prepared struct {
	body          map[string]json.RawMessage
	model         string
	stream        bool
	models        bool
	clientVersion string
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer http.NewResponseController(w).SetWriteDeadline(time.Time{})
	w.Header().Set("Cache-Control", "no-store")
	path := r.URL.Path
	if r.URL.EscapedPath() != path || (path != "/v1/responses" && path != "/v1/models") {
		writeError(w, 404, "invalid_request_error", "Endpoint not supported")
		return
	}
	if (path == "/v1/models" && r.Method != http.MethodGet) || (path == "/v1/responses" && r.Method != http.MethodPost) {
		writeError(w, 405, "invalid_request_error", "Method not supported")
		return
	}
	// Never forward arbitrary query parameters or paths into a privileged upstream request.
	query, queryErr := url.ParseQuery(r.URL.RawQuery)
	if queryErr != nil {
		writeError(w, 400, "invalid_request_error", "Invalid query string")
		return
	}
	for k, v := range query {
		if path != "/v1/models" || k != "client_version" || len(v) != 1 || len(v[0]) > 64 || !clientVersionPattern.MatchString(v[0]) {
			writeError(w, 400, "invalid_request_error", "Unsupported query parameter")
			return
		}
	}
	token := inboundToken(r)
	if !strings.HasPrefix(token, "cxr_") {
		writeError(w, 401, "authentication_error", "A routing token is required")
		return
	}
	p := prepared{models: path == "/v1/models", clientVersion: query.Get("client_version")}
	if !p.models {
		_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(30 * time.Second))
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBody))
		_ = http.NewResponseController(w).SetReadDeadline(time.Time{})
		if err != nil {
			var tooLarge *http.MaxBytesError
			if errors.As(err, &tooLarge) {
				writeError(w, 413, "invalid_request_error", "Request exceeds 16 MiB")
			} else {
				writeError(w, 400, "invalid_request_error", "Cannot read request")
			}
			return
		}
		if json.Unmarshal(body, &p.body) != nil || p.body == nil {
			writeError(w, 400, "invalid_request_error", "Expected a JSON object")
			return
		}
		if json.Unmarshal(p.body["model"], &p.model) != nil || strings.TrimSpace(p.model) == "" {
			writeError(w, 400, "invalid_request_error", "model is required")
			return
		}
		if b, ok := p.body["stream"]; ok && json.Unmarshal(b, &p.stream) != nil {
			writeError(w, 400, "invalid_request_error", "stream must be boolean")
			return
		}
		// Response/conversation IDs are scoped to upstream accounts; silently rotating them can
		// continue another account's conversation or fail after a restart. Require full input.
		for _, field := range []string{"previous_response_id", "conversation"} {
			if b, ok := p.body[field]; ok && string(b) != "null" && string(b) != "\"\"" {
				writeError(w, 400, "unsupported_parameter", field+" is unsupported; resend the full conversation input")
				return
			}
			delete(p.body, field)
		}
		for _, field := range []string{"store", "background"} {
			if b, ok := p.body[field]; ok {
				var value bool
				if json.Unmarshal(b, &value) != nil || value {
					writeError(w, 400, "unsupported_parameter", field+" must be false for stateless account rotation")
					return
				}
			}
		}
		delete(p.body, "background")
		p.body["store"] = json.RawMessage("false")
		if raw := bytes.TrimSpace(p.body["input"]); len(raw) == 0 || (raw[0] != '"' && raw[0] != '[') {
			writeError(w, 400, "invalid_request_error", "input must be a string or an array")
			return
		}
		var instructions string
		if raw, ok := p.body["instructions"]; ok && string(raw) != "null" && json.Unmarshal(raw, &instructions) != nil {
			writeError(w, 400, "invalid_request_error", "instructions must be a string")
			return
		}
		p.body["instructions"], _ = json.Marshal(instructions)
		if field := storedResourceReference(p.body); field != "" {
			writeError(w, 400, "unsupported_parameter", field+" is unsupported on a shared account; resend inline input and use function/custom tools")
			return
		}
	}
	plan, status, err := h.ctrl.ResolveWithModel(r.Context(), token, r.Method, path, p.model)
	if err != nil {
		writeError(w, 502, "api_error", "Control service unavailable")
		return
	}
	if status != 200 {
		if status != 401 && status != 403 {
			status = 502
		}
		writeError(w, status, "authentication_error", "Routing authorization failed")
		return
	}
	activeSession := plan.SessionID
	defer func() { h.ctrl.EndSession(activeSession) }()
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
	defer cancel()
	session := ""
	if !p.models {
		session, err = sessionID(r)
		if err != nil {
			writeError(w, 400, "invalid_request_error", "Invalid conversation session header")
			return
		}
	}
	lease, err := h.affinity.acquire(ctx, token, session)
	if err != nil {
		if ctx.Err() == nil {
			writeError(w, 503, "session_capacity", "Conversation affinity is busy; retry shortly")
		}
		return
	}
	defer lease.Release()
	if lease.Waited {
		// A queued turn can outlive an OAuth refresh, token revocation or permission change.
		// Re-authorize and select from fresh state after acquiring the conversation lock.
		h.ctrl.EndSession(activeSession)
		activeSession = ""
		plan, status, err = h.ctrl.ResolveWithModel(ctx, token, r.Method, path, p.model)
		if err != nil {
			writeError(w, 502, "api_error", "Control service unavailable")
			return
		}
		if status != 200 {
			if status != 401 && status != 403 {
				status = 502
			}
			writeError(w, status, "authentication_error", "Routing authorization failed")
			return
		}
		activeSession = plan.SessionID
	}
	if !p.models && plan.SystemPrompt != nil && *plan.SystemPrompt != "" {
		var instructions string
		_ = json.Unmarshal(p.body["instructions"], &instructions)
		p.body["instructions"], _ = json.Marshal(*plan.SystemPrompt + "\n\n" + instructions)
	}
	candidates := make([]control.Candidate, 0, len(plan.Candidates))
	for _, c := range plan.Candidates {
		if c.Provider == "OPENAI" && (c.Type == "API_KEY" || isOAuth(c.Type)) {
			candidates = append(candidates, c)
		}
	}
	candidates, err = lease.Candidates(candidates, !p.models && requiresAffinity(p.body))
	if err != nil {
		writeError(w, 409, "conversation_account_unavailable", err.Error())
		return
	}
	if len(candidates) == 0 {
		if plan.PriceMissing {
			writeError(w, 403, "pricing_not_configured", "No OpenAI model price is configured for the shared pool; ask an administrator to configure pricing")
			return
		}
		if plan.OverLimit {
			writeError(w, 429, "rate_limit_error", "Daily routing spend limit reached; resets at 00:00 UTC")
		} else {
			writeError(w, 503, "api_error", "No OpenAI account is available")
		}
		return
	}
	headSent := false
	for i, c := range candidates {
		delivered := false
		retry, report := h.attempt(ctx, w, c, plan, p, i+1 < len(candidates), &headSent, &delivered)
		if delivered && !p.models {
			lease.Bind(c)
		}
		h.ctrl.ReportUsage(context.Background(), report)
		if !retry {
			return
		}
	}
}

func inboundToken(r *http.Request) string {
	if auth := r.Header.Get("Authorization"); auth != "" {
		parts := strings.Fields(auth)
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
			return parts[1]
		}
		return ""
	}
	return r.Header.Get("x-api-key")
}

func (h *Handler) request(ctx context.Context, c control.Candidate, p prepared) (*http.Request, error) {
	base := h.cfg.OpenAIAPIBaseURL
	if base == "" {
		base = "https://api.openai.com/v1"
	}
	if isOAuth(c.Type) {
		base = h.cfg.OpenAICodexBaseURL
		if base == "" {
			base = "https://chatgpt.com/backend-api/codex"
		}
	}
	u, err := url.Parse(base)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "https" && u.Scheme != "http") {
		return nil, errors.New("invalid operator upstream URL")
	}
	method := http.MethodPost
	suffix := "/responses"
	var data []byte
	if p.models {
		method = http.MethodGet
		suffix = "/models"
		if isOAuth(c.Type) {
			v := p.clientVersion
			if v == "" {
				v = h.cfg.OpenAIClientVersion
			}
			if v == "" {
				v = config.DefaultOpenAIClientVersion
			}
			suffix += "?client_version=" + url.QueryEscape(v)
		}
	} else {
		body := make(map[string]json.RawMessage, len(p.body))
		for k, v := range p.body {
			body[k] = v
		}
		if isOAuth(c.Type) {
			body["stream"] = json.RawMessage("true")
			body["input"] = codexInput(body["input"])
		}
		data, err = json.Marshal(body)
		if err != nil {
			return nil, err
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(base, "/")+suffix, bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	if !p.models {
		req.Header.Set("Content-Type", "application/json")
		if p.stream || isOAuth(c.Type) {
			req.Header.Set("Accept", "text/event-stream")
		}
	}
	req.Header.Set("User-Agent", "claude-proxy/openai")
	if isOAuth(c.Type) {
		req.Header.Set("originator", "codex_cli_rs")
	}
	// Only these service-owned headers can carry identity. No client headers are copied.
	for k, v := range c.AuthHeaders {
		switch strings.ToLower(k) {
		case "authorization", "chatgpt-account-id", "openai-organization", "openai-project":
			req.Header.Set(k, v)
		}
	}
	if req.Header.Get("Authorization") == "" {
		return nil, errors.New("missing account authorization")
	}
	return req, nil
}

func (h *Handler) attempt(ctx context.Context, w http.ResponseWriter, c control.Candidate, plan *control.ResolveResp, p prepared, canRetry bool, head, delivered *bool) (bool, control.UsageReport) {
	report := control.UsageReport{AccountID: c.AccountID, UserID: plan.UserID, TokenID: plan.TokenID, Source: "routing", Free: p.models, Status: 502, RatelimitHeaders: map[string]string{}}
	if !p.models {
		report.Model = &p.model
	}
	req, err := h.request(ctx, c, p)
	if err != nil {
		if !canRetry {
			fail(w, head, p.stream, 502, "Upstream configuration error")
		}
		return canRetry, report
	}
	resp, err := h.do(ctx, w, req, p.stream, head)
	if err != nil {
		if ctx.Err() != nil {
			report.Status = 499
		}
		if !canRetry && ctx.Err() == nil {
			fail(w, head, p.stream, 502, "OpenAI upstream unavailable")
		}
		return canRetry && ctx.Err() == nil, report
	}
	defer resp.Body.Close()
	report.Status = resp.StatusCode
	report.RatelimitHeaders = rateHeaders(resp.Header)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		errorBody, _ := readBounded(resp.Body, 64<<10)
		if canRetry && (retryable(resp.StatusCode) || modelUnavailable(errorBody)) {
			return true, report
		}
		status := resp.StatusCode
		if status >= 300 && status < 400 {
			status = 502
			report.Status = status
		}
		if !*head {
			copyRateHeaders(w.Header(), resp.Header)
		}
		fail(w, head, p.stream, status, "OpenAI upstream rejected the request")
		return false, report
	}
	if p.models {
		data, err := readBounded(resp.Body, maxResponse)
		if err != nil {
			report.Status = 502
			if !canRetry {
				fail(w, head, false, 502, "Invalid upstream model catalog")
			}
			return canRetry, report
		}
		result, err := modelCatalog(data, isOAuth(c.Type))
		if err != nil {
			report.Status = 502
			if !canRetry {
				fail(w, head, false, 502, "Invalid upstream model catalog")
			}
			return canRetry, report
		}
		writeDeadline(w)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = w.Write(result)
		return false, report
	}
	streamResponse, err := h.responseIsSSE(ctx, w, resp, p.stream, head)
	if err != nil {
		report.Status = 502
		if errors.Is(err, errSSESniffTimeout) {
			report.Status = 504
		}
		if ctx.Err() != nil {
			report.Status = 499
			return false, report
		}
		if !canRetry {
			fail(w, head, p.stream, report.Status, "Cannot read OpenAI response stream")
		}
		return canRetry, report
	}
	if streamResponse {
		return h.relay(ctx, w, resp, p, canRetry, head, delivered, &report), report
	}
	if p.stream || isOAuth(c.Type) {
		report.Status = 502
		if !canRetry {
			fail(w, head, p.stream, 502, "Expected an OpenAI event stream")
		}
		return canRetry, report
	}
	data, err := readBounded(resp.Body, maxResponse)
	if err != nil || !validResponse(data) {
		report.Status = 502
		if !canRetry {
			fail(w, head, false, 502, "Invalid upstream response")
		}
		return canRetry, report
	}
	scanResponse(data, &report)
	var outcome struct {
		Status string `json:"status"`
	}
	_ = json.Unmarshal(data, &outcome)
	if outcome.Status == "failed" {
		report.Status = streamErrorStatus(json.RawMessage(data))
		if canRetry && retryable(report.Status) {
			return true, report
		}
		copyRateHeaders(w.Header(), resp.Header)
		fail(w, head, false, report.Status, "OpenAI response failed")
		return false, report
	}
	copyRateHeaders(w.Header(), resp.Header)
	writeDeadline(w)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	*delivered = true
	_, _ = w.Write(data)
	return false, report
}

func isOAuth(kind string) bool { return kind == "OAUTH" || kind == "OAUTH_STATIC" }

func retryable(status int) bool {
	return status == 401 || status == 403 || status == 429 || status >= 500
}
func readBounded(r io.Reader, n int64) ([]byte, error) {
	b, e := io.ReadAll(io.LimitReader(r, n+1))
	if e == nil && int64(len(b)) > n {
		e = errors.New("response too large")
	}
	return b, e
}
func writeError(w http.ResponseWriter, status int, kind, msg string) {
	writeDeadline(w)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"type": kind, "message": msg, "code": kind}})
}
func fail(w http.ResponseWriter, head *bool, stream bool, status int, message string) {
	writeDeadline(w)
	if !*head {
		writeError(w, status, "upstream_error", message)
		return
	}
	data, _ := json.Marshal(map[string]string{"type": "error", "code": "upstream_error", "message": message})
	_, _ = w.Write(append(append([]byte("event: error\ndata: "), data...), []byte("\n\n")...))
	flush(w)
}
func flush(w http.ResponseWriter) {
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
}
func writeDeadline(w http.ResponseWriter) {
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(30 * time.Second))
}
func startStream(w http.ResponseWriter, head *bool) {
	writeDeadline(w)
	if !*head {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("X-Accel-Buffering", "no")
		w.WriteHeader(200)
		*head = true
		flush(w)
	}
}
func rateHeaders(headers http.Header) map[string]string {
	out := map[string]string{}
	for k, v := range headers {
		key := strings.ToLower(k)
		if strings.HasPrefix(key, "x-codex-") || strings.HasPrefix(key, "x-ratelimit-") || key == "retry-after" {
			out[key] = strings.Join(v, ",")
		}
	}
	return out
}
func copyRateHeaders(dst, src http.Header) {
	for k, v := range rateHeaders(src) {
		dst.Set(k, v)
	}
}

func modelCatalog(data []byte, oauth bool) ([]byte, error) {
	var raw map[string]json.RawMessage
	if json.Unmarshal(data, &raw) != nil {
		return nil, errors.New("invalid catalog")
	}
	if !oauth {
		var models []map[string]any
		if json.Unmarshal(raw["data"], &models) != nil || models == nil {
			return nil, errors.New("missing model data")
		}
		for _, m := range models {
			if id, ok := m["id"].(string); !ok || id == "" {
				return nil, errors.New("invalid model")
			}
		}
		return json.Marshal(map[string]any{"object": "list", "data": models})
	}
	var models []map[string]any
	if json.Unmarshal(raw["models"], &models) != nil || models == nil {
		return nil, errors.New("missing codex catalog")
	}
	list := make([]map[string]any, 0, len(models))
	visible := make([]map[string]any, 0, len(models))
	for _, m := range models {
		if visibility, ok := m["visibility"].(string); ok && visibility != "list" {
			continue
		}
		if enabled, ok := m["enabled"].(bool); ok && !enabled {
			continue
		}
		slug, ok := m["slug"].(string)
		if !ok || slug == "" {
			continue
		}
		visible = append(visible, m)
		list = append(list, map[string]any{"id": slug, "object": "model", "owned_by": "openai", "created": 0})
	}
	// Codex model_catalog_url consumes models; ordinary API clients consume data.
	return json.Marshal(map[string]any{"object": "list", "data": list, "models": visible})
}

// An entitlement mismatch may differ by account; malformed parameters must not be retried.
func modelUnavailable(data []byte) bool {
	var body struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if json.Unmarshal(data, &body) != nil {
		return false
	}
	return body.Error.Code == "model_not_found" || body.Error.Code == "model_not_available" || body.Error.Code == "unsupported_model"
}
func validResponse(data []byte) bool {
	var response struct {
		ID string `json:"id"`
	}
	return json.Unmarshal(data, &response) == nil && response.ID != ""
}
