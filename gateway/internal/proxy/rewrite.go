package proxy

import (
	"encoding/json"
	"regexp"
	"strings"

	"claudeproxy/gateway/internal/ccident"
)

// isTelemetryHeader reports whether a (lowercased) header name is Stainless SDK telemetry that
// must not be forwarded upstream (a proxy tell).
func isTelemetryHeader(ln string) bool {
	return strings.HasPrefix(ln, "x-stainless")
}

// Claude Code encodes per-request identity in the body as `metadata.user_id` — a *string*
// holding escaped JSON `{"device_id":"<64hex>","account_uuid":"","session_id":"<uuid>"}` — and
// repeats the session-id in the X-Claude-Code-Session-Id header. These match the escaped blob.
var (
	userIDRe    = regexp.MustCompile(`"user_id"\s*:\s*"((?:\\.|[^"\\])*)"`)
	deviceIDRe  = regexp.MustCompile(`"device_id"\s*:\s*"[^"]*"`)
	sessionIDRe = regexp.MustCompile(`"session_id"\s*:\s*"[^"]*"`)
)

// accountUUIDRe matches the account_uuid inside the unescaped inner blob.
var accountUUIDRe = regexp.MustCompile(`"account_uuid"\s*:\s*"[^"]*"`)

// rewriteBody stamps the account's device-id and account uuid into the request body and rotates
// the session id so each upstream account presents its own identity. The account uuid is always
// overwritten — with "" for API keys and subscriptions whose uuid is not known yet — because the
// client's value names whoever runs the client, never the account that answers. Port of the Kotlin RequestRewriter. The
// session id is derived deterministically per (origin-session, account) via UUIDv5, so it is
// stable across retries without a database. Returns the (possibly rewritten) body and the value
// to set on X-Claude-Code-Session-Id ("" when the request carried no session id).
func rewriteBody(body []byte, deviceID, accountUUID, headerSessionID string) (out []byte, newSessionID string) {
	if len(body) == 0 {
		return body, ""
	}
	loc := userIDRe.FindSubmatchIndex(body)
	if loc == nil {
		// No metadata blob; still rotate the header session id when the client sent one.
		return body, deriveSession(headerSessionID, deviceID)
	}

	escaped := string(body[loc[2]:loc[3]])
	var innerText string
	if err := json.Unmarshal([]byte(`"`+escaped+`"`), &innerText); err != nil {
		return body, "" // malformed escaped blob; leave untouched
	}
	var inner map[string]any
	if err := json.Unmarshal([]byte(innerText), &inner); err != nil {
		return body, ""
	}
	_, hasDev := inner["device_id"]
	_, hasSid := inner["session_id"]
	_, hasAcc := inner["account_uuid"]
	if !hasDev && !hasSid {
		return body, deriveSession(headerSessionID, deviceID)
	}

	innerSid, _ := inner["session_id"].(string)
	origin := headerSessionID
	if origin == "" {
		origin = innerSid
	}
	newSessionID = deriveSession(origin, deviceID)

	// Rewrite the two identity values inside the (unescaped) inner JSON, then re-escape it.
	newInner := innerText
	if deviceID != "" && hasDev {
		newInner = deviceIDRe.ReplaceAllString(newInner, `"device_id":"`+deviceID+`"`)
	}
	if hasAcc {
		acc, _ := json.Marshal(accountUUID)
		newInner = accountUUIDRe.ReplaceAllLiteralString(newInner, `"account_uuid":`+string(acc))
	}
	if newSessionID != "" && hasSid {
		newInner = sessionIDRe.ReplaceAllString(newInner, `"session_id":"`+newSessionID+`"`)
	}
	reEscaped, err := json.Marshal(newInner)
	if err != nil {
		return body, newSessionID
	}
	// json.Marshal wraps the string in quotes; splice only the escaped content back in.
	reEscapedInner := reEscaped[1 : len(reEscaped)-1]
	out = make([]byte, 0, len(body)+len(reEscapedInner))
	out = append(out, body[:loc[2]]...)
	out = append(out, reEscapedInner...)
	out = append(out, body[loc[3]:]...)
	return out, newSessionID
}

// deriveSession returns a stable per-(origin,account) session id, or "" when origin is empty.
func deriveSession(origin, deviceID string) string {
	if origin == "" {
		return ""
	}
	return ccident.UUIDv5(origin + ":" + deviceID)
}
