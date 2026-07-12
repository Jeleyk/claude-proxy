package proxy

import (
	"strings"
	"testing"

	"claudeproxy/gateway/internal/control"
)

func TestMidStreamErrorOverloadedWhenAltRemains(t *testing.T) {
	cands := []control.Candidate{{AccountID: 1}, {AccountID: 2}}
	frame, status := midStreamError(cands, 0)
	if status != 529 {
		t.Errorf("status = %d, want 529", status)
	}
	if !strings.Contains(frame, "overloaded_error") {
		t.Errorf("frame = %q", frame)
	}
	if !strings.HasPrefix(frame, "event: error\ndata: {") || !strings.HasSuffix(frame, "}\n\n") {
		t.Errorf("frame shape wrong: %q", frame)
	}
}

func TestMidStreamErrorRateLimitOnLastCandidate(t *testing.T) {
	// A far-future reset yields a "retry in Ns" hint.
	cands := []control.Candidate{{AccountID: 1, FiveHourResetEpoch: 1<<62 - 1}}
	frame, status := midStreamError(cands, 0)
	if status != 429 {
		t.Errorf("status = %d, want 429", status)
	}
	if !strings.Contains(frame, "rate_limit_error") {
		t.Errorf("frame = %q", frame)
	}
	if !strings.Contains(frame, "retry in") {
		t.Errorf("expected retry hint: %q", frame)
	}
}

func TestSoonestResetSeconds(t *testing.T) {
	now := int64(1000)
	cands := []control.Candidate{
		{FiveHourResetEpoch: 1200, WeeklyResetEpoch: 5000},
		{FiveHourResetEpoch: 900}, // past → ignored
	}
	if s := soonestResetSeconds(cands, now); s != 200 {
		t.Errorf("soonest = %d, want 200", s)
	}
	if s := soonestResetSeconds([]control.Candidate{{}}, now); s != 0 {
		t.Errorf("no reset should give 0, got %d", s)
	}
}
