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
