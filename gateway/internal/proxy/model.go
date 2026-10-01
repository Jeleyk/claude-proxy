package proxy

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
)

// context1MBeta is the beta Claude Code sends for a "[1m]" model: the suffix never reaches the
// API, it becomes this anthropic-beta token instead.
const context1MBeta = "context-1m-2025-08-07"

// overrideModel forces a token's default model onto the request: the top-level "model" of the
// body is replaced, and a Claude Code style "[1m]" suffix is stripped from it and merged into
// anthropic-beta as the 1M-context beta. Bodies without a top-level model (model listing, GETs)
// are returned untouched, and so are their headers.
//
// The value is spliced in place rather than re-marshalling the whole body: a request is
// hundreds of kilobytes of transcript, and nothing else in it should change on the way through.
func overrideModel(body []byte, h http.Header, model string) []byte {
	id, oneM := splitOneM(model)
	start, end, ok := topLevelModelSpan(body)
	if !ok || id == "" {
		return body
	}
	quoted, err := json.Marshal(id)
	if err != nil {
		return body
	}
	out := make([]byte, 0, len(body)-(end-start)+len(quoted))
	out = append(out, body[:start]...)
	out = append(out, quoted...)
	out = append(out, body[end:]...)
	if oneM {
		h.Set("anthropic-beta", mergeBeta(h.Values("anthropic-beta"), context1MBeta))
	}
	return out
}

// splitOneM separates Claude Code's "[1m]" suffix (any case) from the model id.
func splitOneM(model string) (id string, oneM bool) {
	id = strings.TrimSpace(model)
	if len(id) >= 4 && strings.EqualFold(id[len(id)-4:], "[1m]") {
		return strings.TrimSpace(id[:len(id)-4]), true
	}
	return id, false
}

// topLevelModelSpan finds the byte range of the value of the object's own "model" key — never a
// nested one, such as a tool input that happens to carry a "model" field.
func topLevelModelSpan(body []byte) (start, end int, ok bool) {
	dec := json.NewDecoder(bytes.NewReader(body))
	if t, err := dec.Token(); err != nil || t != json.Delim('{') {
		return 0, 0, false
	}
	for dec.More() {
		t, err := dec.Token()
		if err != nil {
			return 0, 0, false
		}
		key, _ := t.(string)
		var raw json.RawMessage
		if err := dec.Decode(&raw); err != nil {
			return 0, 0, false
		}
		if key == "model" {
			end = int(dec.InputOffset())
			return end - len(raw), end, true
		}
	}
	return 0, 0, false
}
