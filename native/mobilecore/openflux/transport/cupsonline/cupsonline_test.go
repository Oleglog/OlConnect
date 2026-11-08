package cupsonline

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

// A room the relay closed is swapped for a fresh one, and the list the phone
// must follow changes with it: RoomList and the observer both see the swap.
func TestSwapRoomPublishesNewList(t *testing.T) {
	t.Parallel()
	tr := &CupsonlineTransport{}
	tr.roomIDs = []string{"aaaaaaaa-1", "bbbbbbbb-2"}
	var published []string
	tr.OnRoomList(func(packed string) {
		ids, err := unpackRooms(packed)
		if err != nil {
			t.Fatalf("unpackRooms(%q): %v", packed, err)
		}
		published = ids
	})
	tr.setRoomList(tr.roomIDs)

	tr.swapRoomID("aaaaaaaa-1", "cccccccc-3")

	want := []string{"cccccccc-3", "bbbbbbbb-2"}
	if got := strings.Join(tr.roomIDs, ","); got != strings.Join(want, ",") {
		t.Fatalf("roomIDs = %s, want %s", got, strings.Join(want, ","))
	}
	if got := strings.Join(published, ","); got != strings.Join(want, ",") {
		t.Fatalf("published = %s, want %s", got, strings.Join(want, ","))
	}
	if got := tr.RoomList(); got != packRooms(want) {
		t.Fatalf("RoomList() = %q, want %q", got, packRooms(want))
	}
}

// Only the exit creates rooms: a client's closed room cannot be replaced, and
// that refusal is not an error to log.
func TestReplaceDeadRoomIsExitOnly(t *testing.T) {
	t.Parallel()
	tr := &CupsonlineTransport{isClient: true}
	if _, err := tr.replaceDeadRoom("room"); !errors.Is(err, errNoReplace) {
		t.Fatalf("client replace err = %v, want errNoReplace", err)
	}
}

// Swaps are paced: one per RoomReplaceMinInterval, so a burst of closed rooms
// does not look like room-creation abuse to the relay.
func TestReplaceDeadRoomIsPaced(t *testing.T) {
	t.Parallel()
	tr := &CupsonlineTransport{config: DefaultCupsonlineConfig()}
	tr.lastReplace = time.Now()
	if _, err := tr.replaceDeadRoom("room"); !errors.Is(err, errNoReplace) {
		t.Fatalf("paced replace err = %v, want errNoReplace", err)
	}
}

// A closed room with the whole pool down retries within minutes, not in half
// an hour: the relay's refusals are usually a passing rate limit.
func TestRetryDelayCapsWhenWholePoolIsDown(t *testing.T) {
	t.Parallel()
	w := &cupsWS{config: DefaultCupsonlineConfig()}
	w.config.RoomGoneRetryMin = time.Minute
	w.config.RoomGoneRetryMax = 30 * time.Minute
	w.dead.Store(true)
	w.goneDelay = 8 * time.Minute
	w.pool = func() (int, int) { return 0, 8 }
	if d := w.retryDelay(0); d > allPoolDeadRetryMax {
		t.Fatalf("dead pool retry = %v, want <= %v", d, allPoolDeadRetryMax)
	}

	w.pool = func() (int, int) { return 7, 8 }
	w.goneDelay = 8 * time.Minute
	if d := w.retryDelay(0); d != 8*time.Minute {
		t.Fatalf("one live room retry = %v, want 8m", d)
	}
}

// Up is not alive: a peer that stopped answering inside live rooms has to show
// as disconnected, or the tunnel sits there carrying nothing forever.
func TestSilentPeer(t *testing.T) {
	t.Parallel()
	tr := &CupsonlineTransport{}
	if tr.silentPeer() {
		t.Fatal("silentPeer with no traffic sent")
	}

	tr.lastSend.Store(time.Now().Add(-2 * peerSilentAfter).UnixNano())
	if !tr.silentPeer() {
		t.Fatal("silentPeer did not notice a minute of unanswered sending")
	}

	tr.lastRecv.Store(time.Now().UnixNano())
	if tr.silentPeer() {
		t.Fatal("silentPeer said silent while the peer is answering")
	}
}

// liveRooms is what the retry cap and the operator line count: a room marked
// closed is not part of the pool anymore.
func TestLiveRooms(t *testing.T) {
	t.Parallel()
	dead, live := &cupsWS{}, &cupsWS{}
	dead.dead.Store(true)
	tr := &CupsonlineTransport{wss: []*cupsWS{dead, live, live}}
	alive, total := tr.liveRooms()
	if alive != 2 || total != 3 {
		t.Fatalf("liveRooms() = %d/%d, want 2/3", alive, total)
	}
}

// The upper batcher must not fall back to its 5 ms window: on a 10-25 KB/s
// carrier that ships near-empty objects, and the phone's log showed exactly
// that (rtx 492 > tx 421, loss 27-71%, a "4th send" every second).
func TestPreferredBatchLingerIsNotTheFiveMillisecondDefault(t *testing.T) {
	t.Parallel()
	tr := &CupsonlineTransport{config: DefaultCupsonlineConfig()}
	if got := tr.PreferredBatchLingerMs(); got < 100 {
		t.Fatalf("PreferredBatchLingerMs() = %d, want >= 100", got)
	}
}

// OPENFLUX_CUPS_BATCH_LINGER_MS is the knob the stand uses.
func TestBatchLingerEnvOverride(t *testing.T) {
	t.Setenv("OPENFLUX_CUPS_BATCH_LINGER_MS", "40")
	if got := DefaultCupsonlineConfig().BatchLingerMs; got != 40 {
		t.Fatalf("BatchLingerMs = %d, want 40", got)
	}
	t.Setenv("OPENFLUX_CUPS_BATCH_LINGER_MS", "0")
	if got := DefaultCupsonlineConfig().BatchLingerMs; got != 100 {
		t.Fatalf("BatchLingerMs with a zero value = %d, want the 100 ms default", got)
	}
}

// A longer window must not push the upper batch over the carrier's own batch
// limit: the batcher fills up to PreferredBatchBytes and adds a 2-byte length
// per packet (64 packets at most), and the carrier takes BatchMaxBytes.
func TestPreferredBatchFitsTheCarrierLimit(t *testing.T) {
	t.Parallel()
	cfg := DefaultCupsonlineConfig()
	tr := &CupsonlineTransport{config: cfg}
	const maxPackets = 64
	if got := tr.PreferredBatchBytes(); got+2*maxPackets > cfg.BatchMaxBytes {
		t.Fatalf("preferred batch %d + framing %d > carrier limit %d", got, 2*maxPackets, cfg.BatchMaxBytes)
	}
}

// A pong must not wait behind the data path: the ping handler only raises a
// flag, so a held write lock cannot keep it from returning (a pong stuck
// behind queued writes is the 3012 "no pong" close seen on the stand).
func TestPingDoesNotBlockOnTheWritePath(t *testing.T) {
	t.Parallel()
	w := &cupsWS{
		config: DefaultCupsonlineConfig(),
		pongCh: make(chan struct{}, 1),
		ctx:    context.Background(),
		stats:  &channelStats{},
	}
	w.writeMu.Lock() // a data write is in progress
	defer w.writeMu.Unlock()

	done := make(chan struct{})
	go func() {
		w.handleReply([]byte("{}"))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("handleReply blocked on the write path while answering a ping")
	}
	if !w.pongDue.Load() {
		t.Fatal("pong was not marked due")
	}
	if len(w.pongCh) != 1 {
		t.Fatal("pong was not handed to the pong loop")
	}
}

// The relay refuses a write with an RPC error reply: no socket close, no ws
// error, nothing in the log. Before this the refusal was dropped here and the
// sender kept retransmitting into it blind.
func TestWriteRefusalIsCounted(t *testing.T) {
	t.Parallel()
	w := &cupsWS{config: DefaultCupsonlineConfig(), stats: &channelStats{}}

	w.handleReply([]byte(`{"id":7,"error":{"code":7,"message":"slow down"}}`))
	if got := w.stats.writeRefused.Load(); got != 1 {
		t.Fatalf("writeRefused = %d, want 1", got)
	}

	// A plain reply or a push is not a refusal.
	w.handleReply([]byte(`{"id":5,"result":{}}`))
	w.handleReply([]byte(`{"push":{"pub":{"data":{"type":"cursors_update"}}}}`))
	if got := w.stats.writeRefused.Load(); got != 1 {
		t.Fatalf("writeRefused = %d after non-error frames, want 1", got)
	}
}