// Package ccident injects the Claude Code identity into an Anthropic Messages request so that
// OAuth subscription accounts accept traffic that did not originate from Claude Code. The
// load-bearing part is the system prompt: subscription tokens 400/401 unless the FIRST system
// block is exactly the Claude Code prompt (calibrated against live traffic — see CLAUDE.md).
package ccident

import (
	"crypto/sha1"
	"encoding/json"
	"fmt"
	"net/http"
)

// SystemPrompt is the exact first system block Anthropic requires for OAuth subscription auth.
const SystemPrompt = "You are Claude Code, Anthropic's official CLI for Claude."

type textBlock struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

func ccBlock() json.RawMessage {
	b, _ := json.Marshal(textBlock{Type: "text", Text: SystemPrompt})
	return b
}

func textBlockRaw(text string) json.RawMessage {
	b, _ := json.Marshal(textBlock{Type: "text", Text: text})
	return b
}

// firstIsCC reports whether a system block is already the Claude Code prompt.
func firstIsCC(raw json.RawMessage) bool {
	var tb textBlock
	if err := json.Unmarshal(raw, &tb); err == nil {
		return tb.Text == SystemPrompt
	}
	return false
}

// normalizeBlock turns a bare JSON string system element into a text block; other shapes
// (already objects) pass through unchanged.
func normalizeBlock(raw json.RawMessage) json.RawMessage {
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return textBlockRaw(s)
	}
	return raw
}

// InjectSystemPrompt rewrites the Anthropic Messages request `system` field into an array whose
// FIRST block is exactly [SystemPrompt], preserving any client-supplied system content as
// subsequent blocks. If the first block is already the Claude Code prompt it is left untouched.
// Malformed JSON is returned unchanged (the upstream returns a clear error).
func InjectSystemPrompt(body []byte) []byte {
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(body, &obj); err != nil {
		return body
	}
	blocks := []json.RawMessage{ccBlock()}
	if raw, ok := obj["system"]; ok {
		var s string
		if err := json.Unmarshal(raw, &s); err == nil {
			// system was a plain string.
			if s != "" && s != SystemPrompt {
				blocks = append(blocks, textBlockRaw(s))
			}
		} else {
			var arr []json.RawMessage
			if err := json.Unmarshal(raw, &arr); err == nil {
				if len(arr) > 0 && firstIsCC(arr[0]) {
					return body // already injected — leave as-is
				}
				for _, el := range arr {
					blocks = append(blocks, normalizeBlock(el))
				}
			}
			// any other shape: ignore it and use just the Claude Code block.
		}
	}
	nb, err := json.Marshal(blocks)
	if err != nil {
		return body
	}
	obj["system"] = nb
	out, err := json.Marshal(obj)
	if err != nil {
		return body
	}
	return out
}

// SystemBlocks returns the Anthropic `system` array (Claude Code prompt first, then the given
// client system text if non-empty). Used by translators that build the Anthropic body from
// scratch (OpenAI) rather than editing existing JSON.
func SystemBlocks(clientSystem string) []json.RawMessage {
	blocks := []json.RawMessage{ccBlock()}
	if clientSystem != "" {
		blocks = append(blocks, textBlockRaw(clientSystem))
	}
	return blocks
}

// InsertStaticPrompt inserts a per-token static system prompt into an already-prepared Anthropic
// body so it outranks any client-supplied system content: right after the mandatory Claude Code
// block when it is first, else at the very front. Runs after the translator's Prepare, so both
// gateways (translated OpenAI and passthrough Anthropic) get identical semantics. Malformed
// bodies are returned unchanged.
func InsertStaticPrompt(body []byte, prompt string) []byte {
	if prompt == "" || len(body) == 0 {
		return body
	}
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(body, &obj); err != nil {
		return body
	}
	var arr []json.RawMessage
	if raw, ok := obj["system"]; ok {
		if err := json.Unmarshal(raw, &arr); err != nil {
			// system in a non-array shape (plain string): normalize into a block.
			arr = []json.RawMessage{normalizeBlock(raw)}
		}
	}
	at := 0
	if len(arr) > 0 && firstIsCC(arr[0]) {
		at = 1
	}
	arr = append(arr[:at:at], append([]json.RawMessage{textBlockRaw(prompt)}, arr[at:]...)...)
	nb, err := json.Marshal(arr)
	if err != nil {
		return body
	}
	obj["system"] = nb
	out, err := json.Marshal(obj)
	if err != nil {
		return body
	}
	return out
}

// sessionNamespace is a fixed UUID namespace for deriving session ids (UUIDv5). Changing it
// would re-key every derived session at once.
var sessionNamespace = [16]byte{0xc1, 0xad, 0xe0, 0x9e, 0x50, 0x4b, 0x4a, 0x21, 0x9b, 0x3d, 0x7e, 0x11, 0x0c, 0x92, 0x0f, 0x0a}

// UUIDv5 computes an RFC 4122 v5 (SHA-1) UUID from the fixed session namespace and a name.
func UUIDv5(name string) string {
	h := sha1.New()
	h.Write(sessionNamespace[:])
	h.Write([]byte(name))
	s := h.Sum(nil)[:16]
	s[6] = (s[6] & 0x0f) | 0x50 // version 5
	s[8] = (s[8] & 0x3f) | 0x80 // RFC 4122 variant
	return fmt.Sprintf("%x-%x-%x-%x-%x", s[0:4], s[4:6], s[6:8], s[8:10], s[10:16])
}

// userID is metadata.user_id's inner object, fields in the order Claude Code writes them.
type userID struct {
	DeviceID    string `json:"device_id"`
	AccountUUID string `json:"account_uuid"`
	SessionID   string `json:"session_id"`
}

// UserID is the metadata.user_id string Claude Code sends: escaped JSON naming the device, the
// logged-in account ("" in API-key mode) and the session.
func UserID(deviceID, accountUUID, sessionID string) string {
	b, _ := json.Marshal(userID{deviceID, accountUUID, sessionID})
	return string(b)
}

// StampUserID sets the body's metadata to exactly Claude Code's {"user_id": …}, dropping whatever
// metadata the client sent — an SDK's own user_id would name the caller, not the CLI. Malformed
// bodies are returned unchanged.
func StampUserID(body []byte, deviceID, accountUUID, sessionID string) []byte {
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(body, &obj); err != nil || obj == nil {
		return body
	}
	meta, err := json.Marshal(map[string]string{"user_id": UserID(deviceID, accountUUID, sessionID)})
	if err != nil {
		return body
	}
	obj["metadata"] = meta
	out, err := json.Marshal(obj)
	if err != nil {
		return body
	}
	return out
}

// SetClientHeaders adds the headers the CLI sends on every API call, for requests that did not
// come from it: its user agent, `x-app: cli` and the session id the body's metadata repeats.
func SetClientHeaders(h http.Header, userAgent, sessionID string) {
	h.Set("User-Agent", userAgent)
	h.Set("x-app", "cli")
	if sessionID != "" {
		h.Set("X-Claude-Code-Session-Id", sessionID)
	}
}
