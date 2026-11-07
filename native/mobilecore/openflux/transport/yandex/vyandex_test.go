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

// TestDefaultVolgaConfigPinsTheMeasuredStandValues keeps the default profile on
// the numbers the stand was soaked with: three relay workers beat one (6.6-6.9 s
// per page with a single serialised round trip, 4.88-5.46 s with three), five
// already drew 429 bursts, and a batch must carry enough bytes that the relay's
// metered request rate is not the throughput ceiling.
func TestDefaultVolgaConfigPinsTheMeasuredStandValues(t *testing.T) {
	cfg := DefaultVolgaConfig()
	if cfg.WorkerCount != 3 {
		t.Fatalf("WorkerCount = %d, want 3: two and five workers both measured worse on the stand", cfg.WorkerCount)
	}
	if cfg.BatchMaxBytes < 48*1024 {
		t.Fatalf("BatchMaxBytes = %d, want at least 48 KB: each batch is one metered round trip", cfg.BatchMaxBytes)
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
