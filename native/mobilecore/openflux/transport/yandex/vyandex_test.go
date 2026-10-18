package yandex

import (
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestDefaultVolgaConfigUsesOneBatcherAndWireSafeFrames(t *testing.T) {
	cfg := DefaultVolgaConfig()
	if cfg.WorkerCount != 1 {
		t.Fatalf("WorkerCount = %d, want 1", cfg.WorkerCount)
	}
	if got := (&YandexVolgaTransport{}).PreferredBatchBytes(); got > (1<<16)-1 {
		t.Fatalf("PreferredBatchBytes = %d, exceeds uint16 wire length", got)
	}
}

func TestRelayRetriesRateLimitedBatch(t *testing.T) {
	cfg := DefaultVolgaConfig()
	cfg.QueueSize = 1
	cfg.RequestInterval = time.Millisecond
	cfg.RateLimitMaxDelay = time.Millisecond

	stats := &VolgaStats{}
	var auth atomic.Pointer[volgaAuth]
	auth.Store(&volgaAuth{UserID: 1, RequestPath: "test", Token: "token"})
	relay := newRelayClient(&auth, cfg, stats)
	defer relay.cancel()

	var calls atomic.Int32
	relay.httpClient.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		status := http.StatusNoContent
		if calls.Add(1) == 1 {
			status = http.StatusTooManyRequests
		}
		return &http.Response{
			StatusCode: status,
			Header:     make(http.Header),
			Body:       io.NopCloser(strings.NewReader("")),
		}, nil
	})

	if err := relay.sendBatchWithRetry([][]byte{[]byte("packet")}); err != nil {
		t.Fatalf("sendBatchWithRetry: %v", err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("relay attempts = %d, want 2", got)
	}
	if got := stats.HTTPReqsFailed.Load(); got != 1 {
		t.Fatalf("failed requests = %d, want 1", got)
	}
	if got := stats.PacketsSent.Load(); got != 1 {
		t.Fatalf("packets sent = %d, want 1", got)
	}
}
