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
// the numbers the stand was soaked with: three relay workers used to beat one
// (6.6-6.9 s per page with a single serialised round trip, 4.88-5.46 s with
// three), five already drew 429 bursts, and a batch must carry enough bytes that
// the relay's metered request rate is not the throughput ceiling.
//
// Re-measured 2026-11-07 under a real 1 MB transfer: the inflow into the batcher
// is ~8 packets/s, so six workers split it into 1-2 packet batches and the pool
// sat idle (`busy 0-1/6`). Two workers collect more per batch at the same inflow,
// which is what makes BatchMaxBytes reachable at all.
func TestDefaultVolgaConfigPinsTheMeasuredStandValues(t *testing.T) {
	cfg := DefaultVolgaConfig()
	if cfg.WorkerCount != 2 {
		t.Fatalf("WorkerCount = %d, want 2: six workers split an ~8 pkt/s inflow into 1-2 packet batches", cfg.WorkerCount)
	}
	if cfg.WorkerCount > maxVolgaWorkers {
		t.Fatalf("WorkerCount = %d exceeds the hard ceiling %d", cfg.WorkerCount, maxVolgaWorkers)
	}
	if cfg.BatchMaxBytes < 48*1024 {
		t.Fatalf("BatchMaxBytes = %d, want at least 48 KB: each batch is one metered round trip", cfg.BatchMaxBytes)
	}
	if cfg.BatchBulkTimeout < 300*time.Millisecond {
		t.Fatalf("BatchBulkTimeout = %v, want at least 300 ms: a 20 ms window cannot fill a batch at this inflow", cfg.BatchBulkTimeout)
	}
	if cfg.RateLimitMaxDelay > time.Second {
		t.Fatalf("RateLimitMaxDelay = %v, want at most 1 s: one 429 must not park the pool", cfg.RateLimitMaxDelay)
	}
	if got := (&YandexVolgaTransport{}).PreferredBatchBytes(); got > (1<<16)-1 {
		t.Fatalf("PreferredBatchBytes = %d, exceeds uint16 wire length", got)
	}
}

// TestBatcherReachesTheByteCapNotThePacketCap is the invariant this carrier
// burned twice in one day: when a batch is bounded by BOTH a packet counter and a
// byte budget, the packet counter must not trip first or the byte budget is dead
// weight. It is the same defect as vp8.max_sample_bytes (a 1180-byte sample cap
// could never grow because perTickBytes was clamped to it first) and the old
// OPENFLUX_VOLGA_BATCH=10, which capped every request near 12 KB no matter what
// BatchMaxBytes said.
//
// The test feeds a full queue of nominal packets into the batcher and requires the
// batch to reach BatchMaxBytes, and it pins the derivation that guarantees it, so
// neither can regress silently.
func TestBatcherReachesTheByteCapNotThePacketCap(t *testing.T) {
	cfg := DefaultVolgaConfig()
	if cfg.BatchMaxBytes <= 0 || cfg.BatchSize <= 0 {
		t.Fatalf("batch caps not configured: bytes=%d packets=%d", cfg.BatchMaxBytes, cfg.BatchSize)
	}

	// Derived packet cap must be able to hold the byte cap at nominal size.
	wantAtLeast := (cfg.BatchMaxBytes+volgaNominalPacketBytes-1)/volgaNominalPacketBytes
	if cfg.BatchSize < wantAtLeast {
		t.Fatalf("BatchSize = %d, want >= %d: a smaller packet cap makes BatchMaxBytes unreachable", cfg.BatchSize, wantAtLeast)
	}

	stats := &VolgaStats{}
	var auth atomic.Pointer[volgaAuth]
	relay := newRelayClient(&auth, cfg, stats)
	defer relay.cancel()

	// Fill the queue with nominal packets so the batcher has real work to collect.
	packet := make([]byte, volgaNominalPacketBytes)
	packetsNeeded := wantAtLeast + 2
	for i := 0; i < packetsNeeded; i++ {
		relay.batchQueue <- append([]byte(nil), packet...)
	}

	first := <-relay.batchQueue
	batch, totalBytes := relay.collectBatch(first)
	if totalBytes < cfg.BatchMaxBytes {
		t.Fatalf("batch carried %d bytes with %d packets, want at least BatchMaxBytes=%d: the packet cap tripped before the byte cap",
			totalBytes, len(batch), cfg.BatchMaxBytes)
	}
	if len(batch) >= packetsNeeded {
		t.Fatalf("batcher drained the whole queue (%d packets), loop bound is wrong", len(batch))
	}
}

// TestCollectBatchDoesNotHoldAnInteractivePacket guards the other half of the
// same trade-off: with an empty queue a single small packet must go out after the
// short BatchTimeout, not after the bulk window.
func TestCollectBatchDoesNotHoldAnInteractivePacket(t *testing.T) {
	cfg := DefaultVolgaConfig()
	cfg.BatchSize = 30
	cfg.BatchMaxBytes = 48 * 1024
	cfg.BatchTimeout = 20 * time.Millisecond
	cfg.BatchBulkTimeout = 5 * time.Second

	stats := &VolgaStats{}
	var auth atomic.Pointer[volgaAuth]
	relay := newRelayClient(&auth, cfg, stats)
	defer relay.cancel()

	start := time.Now()
	batch, totalBytes := relay.collectBatch(make([]byte, 300))
	elapsed := time.Since(start)
	if len(batch) != 1 || totalBytes != 300 {
		t.Fatalf("batch = %d packets / %d bytes, want the single packet", len(batch), totalBytes)
	}
	if elapsed > time.Second {
		t.Fatalf("collectBatch held an idle packet for %v: the bulk window leaked into the interactive path", elapsed)
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
