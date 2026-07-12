package proxy

import (
	"encoding/json"
	"strings"
	"testing"
)

// buildBody wraps an inner identity blob into a Claude-Code-shaped request body.
func buildBody(t *testing.T, inner string) []byte {
	t.Helper()
	// metadata.user_id is a STRING holding escaped JSON.
	escaped, _ := json.Marshal(inner)
	return []byte(`{"model":"claude-opus-4-8","max_tokens":16,"metadata":{"user_id":` + string(escaped) + `}}`)
}

func TestRewriteBodyReplacesDeviceAndSession(t *testing.T) {
	inner := `{"device_id":"old_device","account_uuid":"","session_id":"origin-sess"}`
	body := buildBody(t, inner)

	out, newSid := rewriteBody(body, "new_device_fp", "")
	if newSid == "" {
		t.Fatal("expected a rotated session id")
	}
	// Extract and unescape the inner blob back out.
	var top map[string]json.RawMessage
	if err := json.Unmarshal(out, &top); err != nil {
		t.Fatalf("output not valid JSON: %v\n%s", err, out)
	}
	var meta map[string]json.RawMessage
	_ = json.Unmarshal(top["metadata"], &meta)
	var userIDStr string
	_ = json.Unmarshal(meta["user_id"], &userIDStr)
	var got map[string]any
	_ = json.Unmarshal([]byte(userIDStr), &got)

	if got["device_id"] != "new_device_fp" {
		t.Errorf("device_id = %v, want new_device_fp", got["device_id"])
	}
	if got["session_id"] != newSid {
		t.Errorf("session_id = %v, want %s", got["session_id"], newSid)
	}
	// Other fields preserved.
	if !strings.Contains(string(out), `"max_tokens":16`) {
		t.Errorf("max_tokens not preserved: %s", out)
	}
}

func TestRewriteBodyWithoutMetadataUnchanged(t *testing.T) {
	body := []byte(`{"model":"m","max_tokens":8}`)
	out, newSid := rewriteBody(body, "dev", "")
	if string(out) != string(body) {
		t.Errorf("body changed: %s", out)
	}
	if newSid != "" {
		t.Errorf("no session id expected, got %q", newSid)
	}
}

func TestRewriteBodyHeaderSessionRotatedWithoutBlob(t *testing.T) {
	body := []byte(`{"model":"m"}`)
	out, newSid := rewriteBody(body, "dev", "client-session")
	if string(out) != string(body) {
		t.Errorf("body should be unchanged: %s", out)
	}
	if newSid == "" {
		t.Error("header session id should be rotated even without a body blob")
	}
}

func TestRewriteBodySessionStableAcrossCalls(t *testing.T) {
	inner := `{"device_id":"d","session_id":"s1"}`
	body := buildBody(t, inner)
	_, sid1 := rewriteBody(body, "acctdev", "hdr-sess")
	_, sid2 := rewriteBody(body, "acctdev", "hdr-sess")
	if sid1 != sid2 || sid1 == "" {
		t.Errorf("session id not stable: %q vs %q", sid1, sid2)
	}
	// Different account (device) → different session id.
	_, sid3 := rewriteBody(body, "otherdev", "hdr-sess")
	if sid3 == sid1 {
		t.Error("different account should yield a different session id")
	}
}
