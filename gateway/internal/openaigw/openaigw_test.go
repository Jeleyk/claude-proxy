package openaigw

import (
	"net/http/httptest"
	"testing"
)

func TestModelsListAndLookup(t *testing.T) {
	tr := New()

	// List.
	rr := httptest.NewRecorder()
	if !tr.HandleLocal(rr, "GET", "/v1/models") || rr.Code != 200 {
		t.Fatalf("models list not served: code=%d", rr.Code)
	}

	// Known model by id.
	rr = httptest.NewRecorder()
	if !tr.HandleLocal(rr, "GET", "/v1/models/claude-opus-4-8") || rr.Code != 200 {
		t.Fatalf("known model should be 200, got %d", rr.Code)
	}

	// Unknown model id -> 404, not a synthesized model.
	rr = httptest.NewRecorder()
	if !tr.HandleLocal(rr, "GET", "/v1/models/gpt-9-nonexistent") {
		t.Fatalf("should be handled locally")
	}
	if rr.Code != 404 {
		t.Fatalf("unknown model must 404, got %d: %s", rr.Code, rr.Body.String())
	}
}

// The service reads "free path" off the URL, so a suffix like /count_tokens must never reach a
// generation through this translator.
func TestPrepareOnlyServesTheExactCompletionsPath(t *testing.T) {
	tr := New()
	body := []byte(`{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}`)
	for _, p := range []string{"/v1/chat/completions", "/v1/chat/completions/"} {
		if _, ok := tr.Prepare(httptest.NewRecorder(), "POST", p, p, body); !ok {
			t.Errorf("%s should be served", p)
		}
	}
	for _, p := range []string{"/v1/chat/completions/count_tokens", "/v1/chat/completionsx"} {
		rr := httptest.NewRecorder()
		if _, ok := tr.Prepare(rr, "POST", p, p, body); ok || rr.Code != 404 {
			t.Errorf("%s should be a 404, got ok=%v code=%d", p, ok, rr.Code)
		}
	}
}
