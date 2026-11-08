package cupsonline

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
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

// encodeFrame is what sendChunk puts in one message.
func encodeFrame(seq uint32, cont bool, body []byte) []byte {
	p := make([]byte, cupsFrameHeader+len(body))
	p[0], p[1] = cupsFrameMagic[0], cupsFrameMagic[1]
	binary.BigEndian.PutUint32(p[2:6], seq)
	if cont {
		p[6] = cupsFrameCont
	}
	binary.BigEndian.PutUint16(p[7:9], uint16(len(body)))
	copy(p[cupsFrameHeader:], body)
	return p
}

// pushFrame wraps a message the way the relay hands it back: a cursors push.
func pushFrame(t *testing.T, sender string, payload []byte) []byte {
	t.Helper()
	msg := map[string]interface{}{
		"push": map[string]interface{}{
			"pub": map[string]interface{}{
				"data": map[string]interface{}{
					"type": "cursors_update",
					"payload": map[string]interface{}{
						"user_uuid": sender,
						"cursors":   cursorsFromBytes(payload),
					},
				},
			},
		},
	}
	raw, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("marshal push: %v", err)
	}
	return raw
}

// packetsFor makes a batch of distinguishable packets.
func packetsFor(n int) [][]byte {
	pkts := make([][]byte, n)
	for i := range pkts {
		pkts[i] = []byte(fmt.Sprintf("packet-%02d", i))
	}
	return pkts
}

func feedFrames(t *testing.T, w *cupsWS, chunks []cupsChunk, seqs ...int) {
	t.Helper()
	for _, i := range seqs {
		w.handleReply(pushFrame(t, "peer", encodeFrame(uint32(i+1), chunks[i].cont, chunks[i].data)))
	}
}

func newRecvWS(limit int) *cupsWS {
	cfg := DefaultCupsonlineConfig()
	cfg.MaxMessageData = limit
	w := &cupsWS{config: cfg, stats: &channelStats{}}
	w.authPtr.Store(&cupsAuth{userUUID: "self", roomUUID: "room"})
	return w
}

// A packet bigger than one message is cut, and the message that continues it
// says so: the far side must not try to parse a packet that starts in a message
// it never saw the beginning of.
func TestFrameChunksMarkTheMessageThatContinuesAPacket(t *testing.T) {
	t.Parallel()
	w := newRecvWS(32)
	big := bytes.Repeat([]byte{'x'}, 80)
	chunks := w.frameChunks([][]byte{big})

	if len(chunks) < 2 {
		t.Fatalf("an 80-byte packet made %d message(s) at 32 bytes each, want a cut", len(chunks))
	}
	if chunks[0].cont {
		t.Fatal("the first message of a packet is marked as a continuation")
	}
	for i, c := range chunks[1:] {
		if !c.cont {
			t.Fatalf("message %d continues a packet but is not marked as one", i+1)
		}
		if len(c.data) > 32 {
			t.Fatalf("message %d carries %d bytes, over the 32-byte limit", i+1, len(c.data))
		}
	}
}

// The whole batch has to survive framing and unpacking unchanged.
func TestFramedBatchRoundTrips(t *testing.T) {
	t.Parallel()
	w := newRecvWS(40)
	pkts := packetsFor(6)
	chunks := w.frameChunks(pkts)
	if len(chunks) < 2 {
		t.Fatalf("framing kept %d messages, want the batch split at 40 bytes", len(chunks))
	}

	var got [][]byte
	w.onData = func(p []byte) { got = append(got, append([]byte(nil), p...)) }
	seqs := make([]int, len(chunks))
	for i := range seqs {
		seqs[i] = i
	}
	feedFrames(t, w, chunks, seqs...)

	if len(got) != len(pkts) {
		t.Fatalf("delivered %d packets, want %d", len(got), len(pkts))
	}
	for i := range pkts {
		if !bytes.Equal(got[i], pkts[i]) {
			t.Fatalf("packet %d = %q, want %q", i, got[i], pkts[i])
		}
	}
}

// This is the stand's failure in one test: the relay refuses a write, that
// message never arrives, and before the sequence numbers the receiver shifted
// every byte after the hole - so the packets that did arrive were garbage and
// the tunnel never recovered. Now the hole costs only its own packets.
func TestLostMessageCostsOnlyItsOwnPackets(t *testing.T) {
	t.Parallel()
	// One packet per message, so a lost message is exactly one lost packet.
	w := newRecvWS(12)
	pkts := packetsFor(6)
	chunks := w.frameChunks(pkts)
	if len(chunks) < 4 {
		t.Fatalf("framing kept %d messages, want at least 4 to lose one in the middle", len(chunks))
	}

	known := map[string]bool{}
	for _, p := range pkts {
		known[string(p)] = true
	}

	var got []string
	w.onData = func(p []byte) {
		if !known[string(p)] {
			t.Fatalf("delivered garbage %q: the stream did not resynchronise", p)
		}
		got = append(got, string(p))
	}

	feedFrames(t, w, chunks, 0) // one good message
	before := len(got)

	// Message 1 is refused by the relay and never arrives.
	feedFrames(t, w, chunks, 2, 3)

	if len(got) <= before {
		t.Fatalf("delivered %d packets before the hole and %d after: the stream stopped at the loss", before, len(got))
	}

	// A message the relay delivers twice must not be delivered twice upward.
	delivered := len(got)
	feedFrames(t, w, chunks, 3)
	if len(got) != delivered {
		t.Fatalf("a replayed message added %d packets, want none", len(got)-delivered)
	}
}