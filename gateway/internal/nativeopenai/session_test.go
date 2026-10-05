package nativeopenai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

func (f *fixture) callSession(body, session string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer cxr_client")
	if session != "" {
		r.Header.Set("session-id", session)
	}
	w := httptest.NewRecorder()
	f.h.ServeHTTP(w, r)
	return w
}

const encryptedRequest = `{"model":"gpt-test","input":[{"type":"reasoning","encrypted_content":"opaque-from-second"}],"stream":true}`
const plaintextRequest = `{"model":"gpt-test","input":[],"stream":true}`

func TestSessionBindsSelectedAccountAndNeverMigratesCiphertext(t *testing.T) {
	var phase atomic.Int32
	var first, second atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("session-id") != "" || r.Header.Get("X-Proxy-Session-ID") != "" {
			t.Error("client session identity leaked upstream")
		}
		if r.Header.Get("Authorization") == "Bearer upstream-1" {
			first.Add(1)
			w.WriteHeader(429)
			return
		}
		second.Add(1)
		if phase.Load() == 1 {
			w.WriteHeader(429)
			return
		}
		sse(w, completed)
	}, account(1, "OAUTH"), account(2, "OAUTH"))
	rec := f.callSession(plaintextRequest, "conversation")
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	f.report(t)
	if first.Load() != 1 || second.Load() != 1 {
		t.Fatal("first request did not rotate")
	}
	phase.Store(1)
	rec = f.callSession(encryptedRequest, "conversation")
	if rec.Code != 429 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	if first.Load() != 1 || second.Load() != 2 {
		t.Fatal("encrypted request migrated", first.Load(), second.Load())
	}
	phase.Store(2)
	rec = f.callSession(encryptedRequest, "conversation")
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	if first.Load() != 1 || second.Load() != 3 {
		t.Fatal("binding not preserved")
	}
}

func TestUnboundEncryptedRequestFailsBeforeUpstream(t *testing.T) {
	var calls atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }, account(1, "OAUTH"))
	for _, session := range []string{"", "new-session"} {
		rec := f.callSession(encryptedRequest, session)
		if rec.Code != 409 || !strings.Contains(rec.Body.String(), "full plaintext") {
			t.Fatal(rec.Code, rec.Body.String())
		}
	}
	if calls.Load() != 0 {
		t.Fatal("unbound ciphertext reached upstream")
	}
}

func TestPartialSemanticStreamBindsItsAccount(t *testing.T) {
	var phase atomic.Int32
	var first atomic.Int32
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer upstream-1" {
			first.Add(1)
			w.WriteHeader(503)
			return
		}
		if phase.Load() == 0 {
			sse(w, "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"encrypted_content\":\"opaque-from-second\"}}\n\nevent: error\ndata: {\"type\":\"error\",\"code\":\"server_error\"}\n\n")
			return
		}
		sse(w, completed)
	}, account(1, "OAUTH"), account(2, "OAUTH"))
	rec := f.callSession(plaintextRequest, "partial")
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "opaque-from-second") {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	f.report(t)
	phase.Store(1)
	rec = f.callSession(encryptedRequest, "partial")
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	f.report(t)
	if first.Load() != 1 {
		t.Fatal("partialstream binding lost")
	}
}

func TestSessionWaitReauthorizesAfterTokenRevocation(t *testing.T) {
	var resolves, upstreams atomic.Int32
	var revoked atomic.Bool
	started := make(chan struct{})
	release := make(chan struct{})
	secondResolved := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreams.Add(1)
		close(started)
		select {
		case <-release:
			sse(w, completed)
		case <-r.Context().Done():
		}
	}))
	defer upstream.Close()
	ctl := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/resolve" {
			w.WriteHeader(204)
			return
		}
		n := resolves.Add(1)
		if revoked.Load() {
			w.WriteHeader(401)
			return
		}
		_ = json.NewEncoder(w).Encode(control.ResolveResp{SessionID: "", Candidates: []control.Candidate{account(1, "OAUTH")}})
		if n == 2 {
			close(secondResolved)
		}
	}))
	defer ctl.Close()
	h := NewHandler(&config.Config{OpenAICodexBaseURL: upstream.URL, UpstreamStallTimeout: time.Second}, control.New(ctl.URL, "internal").WithSource("routing").WithProvider("OPENAI"))
	call := func() *httptest.ResponseRecorder {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		r := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(plaintextRequest)).WithContext(ctx)
		r.Header.Set("Authorization", "Bearer cxr_client")
		r.Header.Set("session-id", "same-session")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	firstDone := make(chan *httptest.ResponseRecorder, 1)
	go func() { firstDone <- call() }()
	<-started
	secondDone := make(chan *httptest.ResponseRecorder, 1)
	go func() { secondDone <- call() }()
	<-secondResolved
	// Wait until the second turn holds a waiter reference, rather than racing HTTP delivery.
	deadline := time.Now().Add(time.Second)
	for {
		h.affinity.mu.Lock()
		entry := h.affinity.entries[affinityKey("cxr_client", "same-session")]
		waiting := entry != nil && entry.users == 2
		h.affinity.mu.Unlock()
		if waiting {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("second request never queued")
		}
		time.Sleep(time.Millisecond)
	}
	revoked.Store(true)
	close(release)
	if rec := <-firstDone; rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	if rec := <-secondDone; rec.Code != 401 {
		t.Fatal("queued revoked token was accepted", rec.Code, rec.Body.String())
	}
	if resolves.Load() != 3 || upstreams.Load() != 1 {
		t.Fatal("queued authorization not refreshed", resolves.Load(), upstreams.Load())
	}
}
