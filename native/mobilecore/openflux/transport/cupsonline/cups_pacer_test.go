package cupsonline

import (
	"context"
	"math"
	"testing"
	"time"
)

// The write pacer finds the relay's budget instead of guessing it: a 429 cuts
// the rate and holds writes, a clean second grows it back towards the ceiling.
func TestWritePacerCutsOnRefusalAndGrows(t *testing.T) {
	t.Parallel()
	cfg := DefaultCupsonlineConfig()
	cfg.RateStart, cfg.RateMin, cfg.RateMax = 40, 5, 200
	cfg.RateGrow, cfg.RateCut, cfg.RateHold = 2, 0.5, 20*time.Millisecond
	cfg.RateBytes, cfg.SendInterval = 0, 0

	w := &cupsWS{config: cfg, ctx: context.Background(), stats: &channelStats{}, rate: cfg.RateStart}
	w.ratePub.Store(math.Float64bits(cfg.RateStart))

	w.stats.writeRefused.Add(1) // the relay answered 429
	start := time.Now()
	if err := w.pace(0); err != nil {
		t.Fatal(err)
	}
	if waited := time.Since(start); waited < 15*time.Millisecond {
		t.Fatalf("a 429 did not hold writes: pace returned after %v, want the 20 ms hold", waited)
	}
	if w.rate != 20 {
		t.Fatalf("rate after 429 = %v, want half of 40", w.rate)
	}

	w.rateAt = time.Now().Add(-2 * time.Second) // a clean second has passed
	if err := w.pace(0); err != nil {
		t.Fatal(err)
	}
	if w.rate <= 20 {
		t.Fatalf("rate = %v after a clean second, want growth", w.rate)
	}
}

// The interval also respects a byte rate, so a large message cannot be written
// faster than the relay swallows it.
func TestWritePacerRespectsTheByteRate(t *testing.T) {
	t.Parallel()
	cfg := DefaultCupsonlineConfig()
	cfg.RateStart, cfg.RateMax = 1000, 1000
	cfg.RateBytes, cfg.SendInterval = 10_000, 0 // 10 KB/s: a 500-byte write waits 50 ms

	w := &cupsWS{config: cfg, ctx: context.Background(), stats: &channelStats{}, rate: cfg.RateStart}
	w.ratePub.Store(math.Float64bits(cfg.RateStart))

	if err := w.pace(500); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	if err := w.pace(500); err != nil {
		t.Fatal(err)
	}
	if waited := time.Since(start); waited < 40*time.Millisecond {
		t.Fatalf("second 500-byte write waited %v, want the byte rate's 50 ms", waited)
	}
}

// A refused write is queued, not dropped: Send only fails when the transport is
// stopped, so the pacer has something to slow down instead of losing data.
func TestSendQueuesInsteadOfDropping(t *testing.T) {
	t.Parallel()
	w := &cupsWS{
		config:    DefaultCupsonlineConfig(),
		ctx:       context.Background(),
		stats:     &channelStats{},
		sendQueue: make(chan []byte, 4),
	}
	for i := 0; i < 4; i++ {
		if err := w.Send([]byte("frame")); err != nil {
			t.Fatalf("Send %d: %v", i, err)
		}
	}
	if len(w.sendQueue) != 4 {
		t.Fatalf("queue holds %d frames, want 4 queued", len(w.sendQueue))
	}
}
