package nativeopenai

import (
	"claudeproxy/gateway/internal/control"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func nativeCandidates(ids ...int) []control.Candidate {
	out := make([]control.Candidate, len(ids))
	for i, id := range ids {
		out[i] = control.Candidate{AccountID: id, Provider: "OPENAI", Type: "OAUTH", AccountUUID: fmt.Sprintf("workspace-%d", id)}
	}
	return out
}
func acquireLease(t *testing.T, s *affinityStore, token, session string) *affinityLease {
	t.Helper()
	lease, err := s.acquire(context.Background(), token, session)
	if err != nil {
		t.Fatal(err)
	}
	return lease
}
func TestAffinitySessionHeaderValidation(t *testing.T) {
	r := httptest.NewRequest("POST", "/v1/responses", nil)
	if id, err := sessionID(r); id != "" || err != nil {
		t.Fatalf("no session %q %v", id, err)
	}
	r.Header.Set("thread-id", "thread")
	r.Header.Set("session-id", "session")
	r.Header.Set("X-Proxy-Session-ID", "explicit")
	if id, err := sessionID(r); id != "explicit" || err != nil {
		t.Fatalf("precedence %q %v", id, err)
	}
	r.Header.Del("X-Proxy-Session-ID")
	if id, _ := sessionID(r); id != "session" {
		t.Fatal(id)
	}
	r.Header.Del("session-id")
	if id, _ := sessionID(r); id != "thread" {
		t.Fatal(id)
	}
	for _, v := range []string{"", "a b", "a\nb", "雪", strings.Repeat("a", 257)} {
		r.Header.Set("X-Proxy-Session-ID", v)
		if _, err := sessionID(r); err == nil {
			t.Fatalf("accepted %q", v)
		}
	}
	r.Header.Set("X-Proxy-Session-ID", "one")
	r.Header.Add("X-Proxy-Session-ID", "two")
	if _, err := sessionID(r); err == nil {
		t.Fatal("duplicate headers")
	}
}
func TestAffinityDetectsEncryptedAndCompactedHistory(t *testing.T) {
	for _, tc := range []struct {
		input string
		want  bool
	}{
		{`"hello encrypted_content"`, false}, {`[{"role":"user","content":"hello"}]`, false},
		{`[{"type":"reasoning","encrypted_content":"opaque"}]`, true},
		{`[{"type":"reasoning","encrypted_content":null}]`, false}, {`[{"type":"reasoning","encrypted_content":""}]`, false},
		{`[{"type":"compaction"}]`, true}, {`[{"type":"compaction_summary","encrypted_content":"opaque"}]`, true},
		{`[{"type":"function_call","encrypted_function_args":"opaque"}]`, true},
		{`[{"content":[{"encrypted_content":{"unexpected":"shape"}}]}]`, true}, {`not-json`, true},
	} {
		if got := requiresAffinity(map[string]json.RawMessage{"input": json.RawMessage(tc.input)}); got != tc.want {
			t.Fatalf("%s got %v", tc.input, got)
		}
	}
}
func TestAffinityEncryptedHistoryCannotRotate(t *testing.T) {
	s := newAffinityStore()
	lease := acquireLease(t, s, "cxr_alice", "thread")
	if _, err := lease.Candidates(nativeCandidates(1, 2), true); !errors.Is(err, errAffinityUnknown) {
		t.Fatal(err)
	}
	lease.Bind(nativeCandidates(2)[0])
	lease.Release()
	lease = acquireLease(t, s, "cxr_alice", "thread")
	defer lease.Release()
	plain, err := lease.Candidates(nativeCandidates(1, 2, 3), false)
	if err != nil || len(plain) != 3 || plain[0].AccountID != 2 || plain[1].AccountID != 1 {
		t.Fatalf("plaintext order %+v %v", plain, err)
	}
	encrypted, err := lease.Candidates(nativeCandidates(1, 2, 3), true)
	if err != nil || len(encrypted) != 1 || encrypted[0].AccountID != 2 {
		t.Fatalf("encrypted rotation %+v %v", encrypted, err)
	}
	if _, err = lease.Candidates(nativeCandidates(1, 3), true); !errors.Is(err, errAffinityUnavailable) {
		t.Fatal("owner unavailable", err)
	}
	if _, err = lease.Candidates([]control.Candidate{{AccountID: 2, Provider: "ANTHROPIC"}}, true); !errors.Is(err, errAffinityUnavailable) {
		t.Fatal("provider mismatch", err)
	}
}
func TestAffinityTokenAndSessionIsolationAndPlainSDK(t *testing.T) {
	s := newAffinityStore()
	lease := acquireLease(t, s, "cxr_alice", "thread")
	lease.Bind(nativeCandidates(7)[0])
	lease.Release()
	for _, key := range [][2]string{{"cxr_bob", "thread"}, {"cxr_alice", "other"}} {
		other := acquireLease(t, s, key[0], key[1])
		if _, err := other.Candidates(nativeCandidates(7), true); !errors.Is(err, errAffinityUnknown) {
			t.Fatal("cross-token/session", err)
		}
		other.Release()
	}
	plain := acquireLease(t, s, "cxr_alice", "")
	defer plain.Release()
	if got, err := plain.Candidates(nativeCandidates(1), false); err != nil || len(got) != 1 {
		t.Fatal("plaintext SDK", err)
	}
	plain.Bind(nativeCandidates(1)[0])
	if _, err := plain.Candidates(nativeCandidates(1), true); !errors.Is(err, errAffinityUnknown) {
		t.Fatal("no session accepted encrypted", err)
	}
	if affinityKey("ab", "c") == affinityKey("a", "bc") {
		t.Fatal("ambiguous framing")
	}
}
func TestAffinityExpiresOrEvictsOnlyIdleEntries(t *testing.T) {
	s := newAffinityStore()
	s.capacity = 2
	now := time.Date(2026, 10, 5, 0, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }
	a := acquireLease(t, s, "token", "active")
	a.Bind(nativeCandidates(1)[0])
	b := acquireLease(t, s, "token", "idle")
	b.Bind(nativeCandidates(2)[0])
	b.Release()
	now = now.Add(time.Hour)
	c := acquireLease(t, s, "token", "new")
	c.Bind(nativeCandidates(3)[0])
	if _, ok := s.entries[affinityKey("token", "active")]; !ok {
		t.Fatal("active evicted")
	}
	if _, ok := s.entries[affinityKey("token", "idle")]; ok {
		t.Fatal("old idle not evicted")
	}
	if _, err := s.acquire(context.Background(), "token", "full"); !errors.Is(err, errAffinityCapacity) {
		t.Fatal("full busy capacity", err)
	}
	now = now.Add(48 * time.Hour)
	if _, err := s.acquire(context.Background(), "token", "still-full"); !errors.Is(err, errAffinityCapacity) {
		t.Fatal("active expired", err)
	}
	a.Release()
	c.Release()
	now = now.Add(24 * time.Hour)
	expired := acquireLease(t, s, "token", "active")
	defer expired.Release()
	if _, err := expired.Candidates(nativeCandidates(1), true); !errors.Is(err, errAffinityUnknown) {
		t.Fatal("expired owner retained", err)
	}
	if len(s.entries) != 1 {
		t.Fatal("expired entries not cleaned")
	}
}
func waitForAffinityUsers(t *testing.T, s *affinityStore, token, session string, users int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		e := s.entries[affinityKey(token, session)]
		matched := e != nil && e.users == users
		s.mu.Unlock()
		if matched {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("waiter did not register")
}
func TestAffinityConcurrentRequestsWaitAndSeeLatestBinding(t *testing.T) {
	s := newAffinityStore()
	owner := acquireLease(t, s, "token", "thread")
	result := make(chan *affinityLease, 1)
	go func() { lease, _ := s.acquire(context.Background(), "token", "thread"); result <- lease }()
	waitForAffinityUsers(t, s, "token", "thread", 2)
	select {
	case <-result:
		t.Fatal("lock bypass")
	default:
	}
	owner.Bind(nativeCandidates(9)[0])
	owner.Release()
	lease := <-result
	if lease == nil {
		t.Fatal("waiter failed")
	}
	defer lease.Release()
	if !lease.Waited {
		t.Fatal("must signal stale authorization plan")
	}
	candidates, err := lease.Candidates(nativeCandidates(3, 9), true)
	if err != nil || len(candidates) != 1 || candidates[0].AccountID != 9 {
		t.Fatal("stale binding", err)
	}
}
func TestAffinityCanceledWaiterDoesNotLoseOwnerOrLeakCapacity(t *testing.T) {
	s := newAffinityStore()
	s.capacity = 1
	owner := acquireLease(t, s, "token", "thread")
	owner.Bind(nativeCandidates(5)[0])
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, err := s.acquire(ctx, "token", "thread"); done <- err }()
	waitForAffinityUsers(t, s, "token", "thread", 2)
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	owner.Release()
	owner.Release()
	next := acquireLease(t, s, "token", "thread")
	if next.Waited {
		t.Fatal("canceled waiter retained lock")
	}
	if _, err := next.Candidates(nativeCandidates(5), true); err != nil {
		t.Fatal("owner lost", err)
	}
	next.Release()
	fresh := acquireLease(t, s, "token", "different")
	fresh.Release()
	if len(s.entries) != 0 {
		t.Fatal("unbound entry leaked")
	}
}
func TestAffinitySerializesConcurrentBindingChanges(t *testing.T) {
	s := newAffinityStore()
	var wg sync.WaitGroup
	var count int
	for range 32 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lease, err := s.acquire(context.Background(), "token", "thread")
			if err != nil {
				t.Error(err)
				return
			}
			count++
			lease.Bind(nativeCandidates(count)[0])
			lease.Release()
		}()
	}
	wg.Wait()
	lease := acquireLease(t, s, "token", "thread")
	defer lease.Release()
	if count != 32 {
		t.Fatal(count)
	}
	if got, err := lease.Candidates(nativeCandidates(32), true); err != nil || len(got) != 1 {
		t.Fatal("last binding lost", err)
	}
}

func TestAffinityRejectsReplacedUpstreamIdentityButAllowsOAuthRefresh(t *testing.T) {
	s := newAffinityStore()
	lease := acquireLease(t, s, "token", "session")
	defer lease.Release()
	original := nativeCandidates(4)[0]
	original.AuthHeaders = map[string]string{"Authorization": "Bearer old", "ChatGPT-Account-Id": "workspace-original"}
	lease.Bind(original)
	refreshed := original
	refreshed.AuthHeaders = map[string]string{"Authorization": "Bearer refreshed", "ChatGPT-Account-Id": "workspace-original"}
	if _, err := lease.Candidates([]control.Candidate{refreshed}, true); err != nil {
		t.Fatal("OAuth refresh changed identity", err)
	}
	replaced := original
	replaced.AuthHeaders = map[string]string{"Authorization": "Bearer refreshed", "ChatGPT-Account-Id": "workspace-new"}
	if _, err := lease.Candidates([]control.Candidate{replaced}, true); !errors.Is(err, errAffinityUnavailable) {
		t.Fatal("changed workspace reused encrypted history", err)
	}
	api := control.Candidate{AccountID: 4, Provider: "OPENAI", Type: "API_KEY", AuthHeaders: map[string]string{"Authorization": "Bearer first-key"}}
	lease.Bind(api)
	api.AuthHeaders = map[string]string{"Authorization": "Bearer second-key"}
	if _, err := lease.Candidates([]control.Candidate{api}, true); !errors.Is(err, errAffinityUnavailable) {
		t.Fatal("changed API key reused encrypted history", err)
	}
}
