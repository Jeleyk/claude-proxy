package ccident

import (
	"encoding/json"
	"testing"
)

// systemBlocks unmarshals the `system` field of an Anthropic body into an array of text blocks.
func systemBlocks(t *testing.T, body []byte) []map[string]string {
	t.Helper()
	var obj struct {
		System json.RawMessage `json:"system"`
	}
	if err := json.Unmarshal(body, &obj); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	var arr []map[string]string
	if err := json.Unmarshal(obj.System, &arr); err != nil {
		t.Fatalf("system is not an array of blocks: %s (%v)", obj.System, err)
	}
	return arr
}

func TestInjectAbsentSystem(t *testing.T) {
	out := InjectSystemPrompt([]byte(`{"model":"claude-x","messages":[]}`))
	blocks := systemBlocks(t, out)
	if len(blocks) != 1 || blocks[0]["text"] != SystemPrompt {
		t.Fatalf("want single CC block, got %v", blocks)
	}
}

func TestInjectStringSystem(t *testing.T) {
	out := InjectSystemPrompt([]byte(`{"system":"Be terse.","messages":[]}`))
	blocks := systemBlocks(t, out)
	if len(blocks) != 2 {
		t.Fatalf("want 2 blocks, got %d: %v", len(blocks), blocks)
	}
	if blocks[0]["text"] != SystemPrompt {
		t.Fatalf("first block must be CC prompt, got %q", blocks[0]["text"])
	}
	if blocks[1]["text"] != "Be terse." {
		t.Fatalf("second block must preserve client system, got %q", blocks[1]["text"])
	}
}

func TestInjectArraySystem(t *testing.T) {
	out := InjectSystemPrompt([]byte(`{"system":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}`))
	blocks := systemBlocks(t, out)
	if len(blocks) != 3 || blocks[0]["text"] != SystemPrompt || blocks[1]["text"] != "A" || blocks[2]["text"] != "B" {
		t.Fatalf("unexpected blocks: %v", blocks)
	}
}

func TestInjectIdempotent(t *testing.T) {
	once := InjectSystemPrompt([]byte(`{"system":"x","messages":[]}`))
	twice := InjectSystemPrompt(once)
	b1 := systemBlocks(t, once)
	b2 := systemBlocks(t, twice)
	if len(b1) != len(b2) {
		t.Fatalf("re-injection changed block count: %d -> %d", len(b1), len(b2))
	}
	if b2[0]["text"] != SystemPrompt {
		t.Fatalf("first block must stay CC prompt")
	}
}

func TestInjectMalformedReturnsInput(t *testing.T) {
	in := []byte(`not json`)
	out := InjectSystemPrompt(in)
	if string(out) != string(in) {
		t.Fatalf("malformed input must be returned unchanged")
	}
}

func TestInsertStaticPromptAfterCCBeforeClient(t *testing.T) {
	body := InjectSystemPrompt([]byte(`{"system":"client rules","messages":[]}`))
	out := InsertStaticPrompt(body, "token rules")
	blocks := systemBlocks(t, out)
	if len(blocks) != 3 || blocks[0]["text"] != SystemPrompt || blocks[1]["text"] != "token rules" || blocks[2]["text"] != "client rules" {
		t.Fatalf("unexpected order: %v", blocks)
	}
}

func TestInsertStaticPromptNoSystem(t *testing.T) {
	out := InsertStaticPrompt([]byte(`{"messages":[]}`), "token rules")
	blocks := systemBlocks(t, out)
	if len(blocks) != 1 || blocks[0]["text"] != "token rules" {
		t.Fatalf("unexpected blocks: %v", blocks)
	}
}

func TestInsertStaticPromptPlainStringSystem(t *testing.T) {
	// Defensive: a body that skipped InjectSystemPrompt (system still a plain string).
	out := InsertStaticPrompt([]byte(`{"system":"client","messages":[]}`), "token rules")
	blocks := systemBlocks(t, out)
	if len(blocks) != 2 || blocks[0]["text"] != "token rules" || blocks[1]["text"] != "client" {
		t.Fatalf("unexpected blocks: %v", blocks)
	}
}

func TestInsertStaticPromptEmptyOrMalformed(t *testing.T) {
	in := []byte(`{"system":"x"}`)
	if out := InsertStaticPrompt(in, ""); string(out) != string(in) {
		t.Fatalf("empty prompt must be a no-op")
	}
	bad := []byte(`not json`)
	if out := InsertStaticPrompt(bad, "p"); string(out) != string(bad) {
		t.Fatalf("malformed input must be returned unchanged")
	}
}
