// Package routing is the shared datapath for the OpenAI/Anthropic API routing gateways. It
// resolves each inbound request against the service control API (source="routing"), forwards a
// translated Anthropic Messages request across the ordered candidate accounts with transparent
// retry, relays the response (streaming or buffered) through a protocol Translator, and reports
// usage back. It mirrors the production Claude Code gateway's SSE handling — flush the head
// immediately, true-stream, inject keep-alive comments during upstream silence — which is
// load-bearing (see CLAUDE.md / the 502 investigation).
package routing

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"claudeproxy/gateway/internal/anthropic"
	"claudeproxy/gateway/internal/ccident"
	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

// keepAliveInterval bounds how long the relay waits during upstream silence before emitting an
// SSE keep-alive comment. Adaptive-thinking Opus can stay silent 30s+ before the first event.
const keepAliveInterval = 15 * time.Second

// Usage is the token usage + model + recorded status scanned from an upstream response. It is
// an alias, not a copy, of the wire-level type: pricing fields (per-TTL cache writes, server
// tool calls, fast mode) must never be dropped on the way from the parser to the usage report.
type Usage = anthropic.Usage

// Prepared is a translated, ready-to-forward upstream request.
type Prepared struct {
	Method         string   // upstream HTTP method (usually the inbound one)
	UpstreamPath   string   // upstream path (+query), e.g. "/v1/messages"
	Body           []byte   // Anthropic Messages JSON (Claude Code prompt injected); nil for GET
	RequestedModel string   // model to report for usage/pricing (the resolved Claude model)
	Stream         bool     // client asked for a streamed response
	Beta           []string // client anthropic-beta values to merge with the account's
	// State carries protocol-specific per-request data a Translator needs across Prepare →
	// NewStreamWriter/RelayJSON (e.g. the OpenAI echo model, request id, include_usage flag).
	State any
}

// StreamWriter consumes raw upstream (Anthropic) SSE bytes and writes client-protocol SSE frames
// to its underlying writer, scanning usage as it goes. Feed may be called with arbitrary chunk
// boundaries; Finish emits any terminal frames (e.g. OpenAI's `data: [DONE]`).
type StreamWriter interface {
	Feed(p []byte)
	Finish()
	Usage() Usage
}

// Translator adapts one client API protocol (OpenAI, Anthropic) to the Anthropic Messages upstream.
type Translator interface {
	// Name identifies the client protocol (for logs).
	Name() string
	// HandleLocal serves endpoints that need no upstream (e.g. model listing). Returns true if
	// it fully handled the request. Called only after the token is authenticated.
	HandleLocal(w http.ResponseWriter, method, path string) bool
	// Prepare turns an inbound request into an upstream Anthropic request. ok=false means the
	// request was invalid and an error was already written to w.
	Prepare(w http.ResponseWriter, method, path, requestURI string, body []byte) (Prepared, bool)
	// NewStreamWriter builds a per-request streaming translator writing to w.
	NewStreamWriter(w io.Writer, p Prepared) StreamWriter
	// RelayJSON translates a buffered upstream response (any status) and writes it to the client,
	// returning the scanned usage.
	RelayJSON(w http.ResponseWriter, status int, upstream []byte, p Prepared) Usage
	// StreamContentType is the Content-Type for streamed client responses.
	StreamContentType() string
	// WriteError writes a client-shaped error with the given HTTP status.
	WriteError(w http.ResponseWriter, status int, kind, message string)
}

// Handler is the routing datapath HTTP handler.
type Handler struct {
	cfg      *config.Config
	ctrl     *control.Client
	tr       Translator
	upstream *http.Client
}

// NewHandler builds a routing handler. The upstream client has NO overall timeout (SSE streams
// run for minutes) but bounds dial + TLS handshake so a dead upstream fails fast.
func NewHandler(cfg *config.Config, ctrl *control.Client, tr Translator) *Handler {
	return &Handler{
		cfg:  cfg,
		ctrl: ctrl,
		tr:   tr,
		upstream: &http.Client{
			Transport: &http.Transport{
				DialContext:           (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
				TLSHandshakeTimeout:   10 * time.Second,
				ResponseHeaderTimeout: 0,
				ExpectContinueTimeout: 1 * time.Second,
				MaxIdleConns:          100,
				IdleConnTimeout:       90 * time.Second,
				ForceAttemptHTTP2:     true,
			},
		},
	}
}

// retryableStatuses make a non-last attempt retry the next account instead of passing through.
var retryableStatuses = map[int]bool{429: true, 401: true, 500: true, 502: true, 503: true, 529: true}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	token := extractInboundToken(r)
	if token == "" {
		h.tr.WriteError(w, http.StatusUnauthorized, "authentication_error", "Missing API key")
		return
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.tr.WriteError(w, http.StatusBadRequest, "invalid_request_error", "Failed to read request body")
		return
	}

	requestURI := r.URL.RequestURI()
	resp, status, err := h.ctrl.Resolve(r.Context(), token, r.Method, requestURI)
	if err != nil {
		h.tr.WriteError(w, http.StatusBadGateway, "api_error", "control API unreachable")
		return
	}
	switch status {
	case http.StatusUnauthorized:
		h.tr.WriteError(w, http.StatusUnauthorized, "authentication_error", "Invalid routing token")
		return
	case http.StatusForbidden:
		h.tr.WriteError(w, http.StatusForbidden, "permission_error", "Token lacks routing.use")
		return
	case http.StatusOK:
		// fall through
	default:
		h.tr.WriteError(w, http.StatusBadGateway, "api_error", "control API error")
		return
	}
	// The resolve opened an "active now" entry for this request; close it however we leave.
	defer h.ctrl.EndSession(resp.SessionID)

	// Endpoints served locally (model listing) — authenticated, no upstream, no usage.
	if h.tr.HandleLocal(w, r.Method, r.URL.Path) {
		return
	}

	if len(resp.Candidates) == 0 {
		if resp.OverLimit {
			h.tr.WriteError(w, http.StatusTooManyRequests, "rate_limit_error",
				"Daily routing spend limit reached; resets at 00:00 UTC.")
			return
		}
		h.tr.WriteError(w, http.StatusServiceUnavailable, "rate_limit_error", "No account is available for routing")
		return
	}

	prep, ok := h.tr.Prepare(w, r.Method, r.URL.Path, requestURI, body)
	if !ok {
		return // error already written
	}
	// Per-token static system prompt: inject right behind the Claude Code block, ahead of any
	// client-supplied system, so the token owner's instructions outrank what arrives in the API.
	if resp.SystemPrompt != nil && *resp.SystemPrompt != "" && len(prep.Body) > 0 {
		prep.Body = ccident.InsertStaticPrompt(prep.Body, *resp.SystemPrompt)
	}
	prep.Beta = append(prep.Beta, r.Header.Values("anthropic-beta")...)

	// headSent survives across attempts: once the streamed 200 is open (because an attempt got a
	// 200 and only then hit an overload), the next account writes into that same response.
	headSent := false
	for i, cand := range resp.Candidates {
		canRetry := i < len(resp.Candidates)-1
		res := h.forward(r.Context(), w, cand, resp.UserID, resp.TokenID, prep, canRetry, headSent)
		h.ctrl.ReportUsage(context.Background(), res.report)
		headSent = res.headSent
		if !res.retry {
			return
		}
	}
}

type forwardResult struct {
	retry  bool
	report control.UsageReport
	// headSent means the streamed 200 head is already on the wire, so a retry must continue
	// writing into the open response rather than starting a new one.
	headSent bool
}

func (h *Handler) forward(
	ctx context.Context, w http.ResponseWriter, cand control.Candidate, userID, tokenID *int, prep Prepared, canRetry, headSent bool,
) forwardResult {
	model := prep.RequestedModel
	report := control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Source: "routing"}

	url := h.cfg.UpstreamBaseURL + prep.UpstreamPath
	var reqBody io.Reader
	if len(prep.Body) > 0 {
		reqBody = bytes.NewReader(prep.Body)
	}
	req, err := http.NewRequestWithContext(ctx, prep.Method, url, reqBody)
	if err != nil {
		report.Status = 0
		return forwardResult{retry: canRetry, report: report}
	}
	if len(prep.Body) > 0 {
		req.Header.Set("Content-Type", "application/json")
		req.ContentLength = int64(len(prep.Body))
	}
	// Per-account upstream credentials (already decrypted by the service). Merge anthropic-beta
	// with any client-provided values so client betas (e.g. context-management, prompt-caching)
	// survive alongside the account's oauth beta.
	for k, v := range cand.AuthHeaders {
		if strings.EqualFold(k, "anthropic-beta") {
			req.Header.Set("anthropic-beta", mergeBeta(prep.Beta, v))
		} else {
			req.Header.Set(k, v)
		}
	}
	if req.Header.Get("anthropic-beta") == "" && len(prep.Beta) > 0 {
		req.Header.Set("anthropic-beta", strings.Join(dedupCSV(prep.Beta), ","))
	}
	req.Header.Set("anthropic-version", "2023-06-01")

	resp, err := h.upstream.Do(req)
	if err != nil {
		if canRetry {
			report.Status = 0
			return forwardResult{retry: true, headSent: headSent, report: report}
		}
		h.failAfterHead(w, headSent, http.StatusBadGateway, "api_error", "upstream request failed")
		report.Status = http.StatusBadGateway
		return forwardResult{retry: false, headSent: headSent, report: report}
	}
	defer resp.Body.Close()

	report.Status = resp.StatusCode
	report.RatelimitHeaders = extractRateLimitHeaders(resp.Header)

	if retryableStatuses[resp.StatusCode] && canRetry {
		_, _ = io.Copy(io.Discard, resp.Body)
		return forwardResult{retry: true, headSent: headSent, report: report}
	}

	contentType := resp.Header.Get("Content-Type")
	if strings.Contains(strings.ToLower(contentType), "text/event-stream") {
		sw := h.tr.NewStreamWriter(w, prep)
		u, retry := h.relayStream(w, resp.Body, sw, headSent, canRetry)
		applyUsage(&report, u, model)
		return forwardResult{retry: retry, headSent: true, report: report}
	}

	buf, _ := io.ReadAll(resp.Body)
	if headSent {
		// An earlier attempt opened a streamed 200 and then overloaded before any content; this
		// one came back non-streamed, so the status line is spent. Close the open stream with a
		// client-shaped error instead of truncating it silently.
		h.failAfterHead(w, true, resp.StatusCode, "api_error", "upstream returned a non-streamed response")
		return forwardResult{retry: false, headSent: true, report: report}
	}
	u := h.tr.RelayJSON(w, resp.StatusCode, buf, prep)
	applyUsage(&report, u, model)
	return forwardResult{retry: false, headSent: false, report: report}
}

// failAfterHead reports a terminal failure in whichever form the response still allows: a normal
// client-shaped error when nothing has been sent, or an error frame on an already-open stream.
func (h *Handler) failAfterHead(w http.ResponseWriter, headSent bool, status int, kind, message string) {
	if !headSent {
		h.tr.WriteError(w, status, kind, message)
		return
	}
	// The status line is gone; an SSE comment is the only thing every client tolerates, and the
	// truncated stream itself signals the failure.
	_, _ = io.WriteString(w, ": claude-proxy: "+message+"\n\n")
	if fl, ok := w.(http.Flusher); ok {
		fl.Flush()
	}
}

// relayStream streams an SSE response to the client in real time while feeding usage/translation
// through the StreamWriter. It flushes the head immediately and injects keep-alive comments
// during upstream silence, exactly like the production gateway.
//
// Anthropic can accept a request with a 200 and only then fail it with an in-stream
// `overloaded_error`. When that happens before the translator has emitted anything client-visible,
// the second return value asks the caller to hand the request to the next account — which takes
// over this same open stream, invisibly to the client. Once real frames are out, a swap would be
// visible (duplicated content), so the stream is simply closed and the client retries.
func (h *Handler) relayStream(
	w http.ResponseWriter, upstream io.ReadCloser, sw StreamWriter, headSent, canRetry bool,
) (Usage, bool) {
	fl, _ := w.(http.Flusher)
	if !headSent {
		w.Header().Set("Content-Type", h.tr.StreamContentType())
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, ": keep-alive\n\n")
		if fl != nil {
			fl.Flush()
		}
	}

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

	var errs anthropic.ErrScan
	wroteData := false
	ticker := time.NewTicker(keepAliveInterval)
	defer ticker.Stop()
	for {
		select {
		case b := <-dataCh:
			errs.Feed(b)
			if errs.Retryable() != "" && !wroteData && canRetry {
				// Nothing client-visible yet — let another account serve this request instead of
				// translating an upstream overload into a client-facing failure.
				return sw.Usage(), true
			}
			sw.Feed(b)
			wroteData = true
			if fl != nil {
				fl.Flush()
			}
		case <-ticker.C:
			_, _ = io.WriteString(w, ": keep-alive\n\n")
			if fl != nil {
				fl.Flush()
			}
		case <-errCh:
			sw.Finish()
			if fl != nil {
				fl.Flush()
			}
			return sw.Usage(), false
		}
	}
}

func applyUsage(report *control.UsageReport, u Usage, fallbackModel string) {
	u.Normalize()
	report.Input, report.Output = u.Input, u.Output
	report.CacheRead, report.CacheWrite = u.CacheRead, u.CacheWrite
	report.CacheWrite1h = u.CacheWrite1h
	report.WebSearchRequests, report.WebFetchRequests = u.WebSearch, u.WebFetch
	report.Fast = u.Fast
	model := u.Model
	if model == "" {
		model = fallbackModel
	}
	if model != "" {
		report.Model = &model
	}
	if u.Status != 0 {
		report.Status = u.Status
	}
}

// extractInboundToken reads the routing token from x-api-key (Anthropic SDK) or
// Authorization: Bearer (OpenAI SDK).
func extractInboundToken(r *http.Request) string {
	if v := strings.TrimSpace(r.Header.Get("x-api-key")); v != "" {
		return v
	}
	auth := r.Header.Get("Authorization")
	if auth == "" {
		return ""
	}
	return strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
}

// mergeBeta merges the account's anthropic-beta token(s) into the client's, de-duplicated,
// client values first. Mirrors the production gateway's mergeBeta.
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

func dedupCSV(vals []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, v := range vals {
		for _, tok := range strings.Split(v, ",") {
			t := strings.TrimSpace(tok)
			if t == "" || seen[t] {
				continue
			}
			seen[t] = true
			out = append(out, t)
		}
	}
	return out
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
