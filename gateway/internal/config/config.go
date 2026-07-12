// Package config loads the gateway's runtime configuration from environment variables.
package config

import "os"

// Config is the gateway's runtime configuration.
type Config struct {
	// Port the gateway listens on (env PORT, default "9000").
	Port string
	// ServiceURL is the base URL of the Kotlin service's control API (env SERVICE_URL).
	ServiceURL string
	// InternalToken is the shared secret sent as X-Internal-Token to the control API.
	InternalToken string
	// UpstreamBaseURL is the Anthropic API base (env UPSTREAM_BASE_URL).
	UpstreamBaseURL string
}

// Load reads the configuration from the environment, applying defaults.
func Load() *Config {
	return &Config{
		Port:            envOr("PORT", "9000"),
		ServiceURL:      envOr("SERVICE_URL", "http://service:8787"),
		InternalToken:   os.Getenv("INTERNAL_TOKEN"),
		UpstreamBaseURL: envOr("UPSTREAM_BASE_URL", "https://api.anthropic.com"),
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
