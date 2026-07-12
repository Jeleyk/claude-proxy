package proxy

import "strings"

// isTelemetryHeader reports whether a (lowercased) header name is Stainless SDK telemetry that
// must not be forwarded upstream (a proxy tell).
func isTelemetryHeader(ln string) bool {
	return strings.HasPrefix(ln, "x-stainless")
}

// rewriteBody stamps the account's device-id into the request body and rotates the session id.
// Minimal pass-through here; the faithful port of RequestRewriter lands in Task B5.
func rewriteBody(body []byte, deviceID, headerSessionID string) (out []byte, newSessionID string) {
	return body, ""
}
