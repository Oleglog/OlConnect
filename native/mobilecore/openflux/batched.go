package openflux

import (
	"fmt"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// Defaults for the coalescing layer. Tunable at runtime via env vars
// (OPENFLUX_BATCH_BYTES / OPENFLUX_BATCH_COUNT / OPENFLUX_BATCH_LINGER_MS).
const (
	defaultMaxBatchBytes = 8192
	defaultMaxBatchCount = 64
	defaultLingerMs      = 5
	batchQueueDepth      = 4096
)

// BatchedTransport queues outgoing tunnel packets, coalesces bursts into a single
// framed+zstd batch per inner transport message, and splits batches back into packets on receive.
type BatchedTransport struct {
	Transport

	queue         chan []byte
	lingerMs      int
	maxBatchBytes int
	maxBatchCount int

	running  atomic.Bool
	stopChan chan struct{}
	stopOnce sync.Once

	mu     sync.RWMutex
	userCb func([]byte)
}

func envInt(name string, def int) int {
	if v := os.Getenv(name); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return def
}

func NewBatchedTransport(inner Transport) *BatchedTransport {
	return &BatchedTransport{
		Transport:     inner,
		queue:         make(chan []byte, batchQueueDepth),
		stopChan:      make(chan struct{}),
		lingerMs:      envInt("OPENFLUX_BATCH_LINGER_MS", defaultLingerMs),
		maxBatchBytes: envInt("OPENFLUX_BATCH_BYTES", defaultMaxBatchBytes),
		maxBatchCount: envInt("OPENFLUX_BATCH_COUNT", defaultMaxBatchCount),
	}
}

func (b *BatchedTransport) Start() error {
	if err := b.Transport.Start(); err != nil {
		return err
	}
	b.running.Store(true)
	go b.flushLoop()
	return nil
}

func (b *BatchedTransport) Stop() error {
	b.running.Store(false)
	b.stopOnce.Do(func() {
		close(b.stopChan)
	})
	return b.Transport.Stop()
}

// Send copies the packet and enqueues it for batching.
func (b *BatchedTransport) Send(data []byte) error {
	p := make([]byte, len(data))
	copy(p, data)
	select {
	case b.queue <- p:
		return nil
	default:
		return fmt.Errorf("batch queue full")
	}
}

func (b *BatchedTransport) deliver(pkt []byte) {
	b.mu.RLock()
	cb := b.userCb
	b.mu.RUnlock()
	if cb != nil {
		cb(pkt)
	}
}

func (b *BatchedTransport) Receive(callback func([]byte)) {
	b.mu.Lock()
	b.userCb = callback
	b.mu.Unlock()

	b.Transport.Receive(func(data []byte) {
		if len(data) == 0 {
			return
		}
		// Fallback: if data is from a legacy unbatched peer (marker 0x00, 0x1F, or raw IP 0x45/0x60)
		if data[0] != batchFormatVersion {
			decompressed, err := decompress(data)
			if err != nil {
				b.deliver(data)
				return
			}
			b.deliver(decompressed)
			return
		}

		pkts, err := decodeFramedBatch(data)
		if err != nil {
			Debugf("[BATCH] decode error (%d bytes): %v", len(data), err)
			return
		}
		b.mu.RLock()
		cb := b.userCb
		b.mu.RUnlock()
		if cb == nil {
			return
		}
		for _, p := range pkts {
			cb(p)
		}
	})
}

func (b *BatchedTransport) flushLoop() {
	for b.running.Load() {
		var first []byte
		var ok bool
		select {
		case <-b.stopChan:
			return
		case first, ok = <-b.queue:
			if !ok {
				return
			}
		}

		batch := [][]byte{first}
		size := 2 + len(first)

		// Phase 1: absorb everything already queued (burst coalescing).
	drainNow:
		for size < b.maxBatchBytes && len(batch) < b.maxBatchCount {
			select {
			case <-b.stopChan:
				return
			case p, ok := <-b.queue:
				if !ok {
					_ = b.Transport.Send(encodeFramedBatch(batch))
					return
				}
				batch = append(batch, p)
				size += 2 + len(p)
			default:
				break drainNow
			}
		}

		// Phase 2: brief linger to catch stragglers arriving just after the burst.
		if b.lingerMs > 0 && size < b.maxBatchBytes && len(batch) < b.maxBatchCount {
			timer := time.NewTimer(time.Duration(b.lingerMs) * time.Millisecond)
		linger:
			for size < b.maxBatchBytes && len(batch) < b.maxBatchCount {
				select {
				case <-b.stopChan:
					timer.Stop()
					return
				case p, ok := <-b.queue:
					if !ok {
						timer.Stop()
						_ = b.Transport.Send(encodeFramedBatch(batch))
						return
					}
					batch = append(batch, p)
					size += 2 + len(p)
				case <-timer.C:
					break linger
				}
			}
			timer.Stop()
		}

		_ = b.Transport.Send(encodeFramedBatch(batch))
	}
}
