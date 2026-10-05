package nativeopenai

import (
	"context"
	"testing"

	"claudeproxy/gateway/internal/config"
)

func TestCodexCatalogClientVersionDefaultAndPrecedence(t *testing.T) {
	t.Setenv("OPENAI_CLIENT_VERSION", "")
	loaded := config.Load()
	for _, tc := range []struct{ name, configured, caller, want string }{
		{"loaded default", loaded.OpenAIClientVersion, "", "0.159.2"},
		{"empty config fallback", "", "", "0.159.2"},
		{"operator override", "0.200.1", "", "0.200.1"},
		{"caller override", "0.200.1", "0.210.2", "0.210.2"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := NewHandler(&config.Config{OpenAICodexBaseURL: "https://catalog.example/codex", OpenAIClientVersion: tc.configured}, nil)
			req, err := h.request(context.Background(), account(1, "OAUTH"), prepared{models: true, clientVersion: tc.caller})
			if err != nil {
				t.Fatal(err)
			}
			if got := req.URL.Query().Get("client_version"); got != tc.want {
				t.Errorf("catalog client_version = %q, want %q", got, tc.want)
			}
			if req.URL.Path != "/codex/models" || len(req.URL.Query()) != 1 {
				t.Fatal("catalog request shape changed")
			}
		})
	}
}
