// Package mailru implements a transport that tunnels packets through
// Mail.ru's cloud document editor (docs.datacloudmail.ru), the same
// coauthoring backend family as Yandex.Docs. Two peers open the same
// public document and smuggle packets through the "cursor" field of the
// collaborative editing protocol.
package mailru

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"

	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/utils"
)

// Mail.ru rejects the old Chrome/137 UA on the WebSocket upgrade with HTTP 409
// on some edge nodes; a current Firefox UA is accepted for both the API and the
// WebSocket. Reported by @Shiller70 (issue #164).
const mailruUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"

var cursorPayloadRe = regexp.MustCompile(`"cursor":"[^;]+;([^"]+)"`)

// errMailruAuthRequired marks a document that needs a signed-in session. No
// number of retries produces cookies, so the transport stops instead of looping.
var errMailruAuthRequired = errors.New("document requires authentication (supply --cookies /path/to/cookies.txt)")

// saveChangesMessageTemplate — шаблон saveChanges-сообщения в формате
// веб-клиента Mail.ru: две подстановки %s — UserId и UserShortId в
// excelAdditionalInfo. Остальные поля (op-ы в changes, анкоры, версия)
// оставлены ровно такими, какими их шлёт живой редактор; это шаблон, а не
// «правильная» структура — формат оп закрытый, и мы только подставляем свои
// значения участника.
//
// Поля isCoAuthoring/releaseLocks = false — особенность именно mail.ru
// (у yandex.docs там true/true).
const saveChangesMessageTemplate = `42["message",{"type":"saveChanges","changes":"[\"76;AgAAADEA//8BAOwbfF7pEAAALQEAAAMAAAAAAAAAAAAAAAAAAAAAAAAA9v///xoAAAAyADAAMgA2AC4AMgAuADEALgAyADIANgA4AA==\",\"35;BgAAADYAMgA3AAEAHAABAAAAAAAAAAEAAABhAAAAAAMAAAA=\",\"35;BgAAADYAMgA3AAEAHAABAAAAAQAAAAEAAABzAAAAAAMAAAA=\",\"35;BgAAADYAMgA3AAEAHAABAAAAAgAAAAEAAABkAAAAAAMAAAA=\"]","startSaveChanges":true,"endSaveChanges":true,"isCoAuthoring":false,"isExcel":false,"deleteIndex":null,"excelAdditionalInfo":"{\"UserId\":\"%s\",\"UserShortId\":\"%s\",\"CursorInfo\":\"14;BgAAADYAMgA3AAMAAAA=\"}","unlock":false,"releaseLocks":false}]`

type MailruDocsInfo struct {
	Token        string
	DocKey       string
	WsURL        string
	FileType     string
	DocURL       string
	DocTitle     string
	Permissions  map[string]interface{}
	CallbackURL  string
	EditorUserID string
}

type DocSession struct {
	Info       MailruDocsInfo
	Conn       *websocket.Conn
	WriteQueue chan []byte
	UserID     string
	writeMu    sync.Mutex

	// ready is closed once co-editing writes are allowed on this session:
	// either we have replied authChangesAck to the server's authChanges, or
	// the readiness fallback fired (a legacy server that never sends
	// authChanges). The write paths hold their first co-editing write until
	// it is closed — see markReady and handleMessage.
	ready     chan struct{}
	readyOnce sync.Once

	// authed is closed once the server has accepted this connection's editor
	// auth (it sends type:"auth" result:1). A make-before-break rotation waits
	// for it before moving outgoing traffic onto the new connection, so a packet
	// is never sent on a connection the server will not yet relay (which would
	// otherwise be lost until the Session ARQ retransmits a whole RTO later — a
	// visible stall). See markAuthed and rotateCarrier.
	authed     chan struct{}
	authedOnce sync.Once

	// retired is set once a make-before-break rotation has finished with this
	// session (after the brief receive-overlap grace). Its reader then stops
	// delivering and exits silently on close without scheduling a reconnect. The
	// multi-identity echo filter (MailruDocsTransport.ourIDs) lets the old reader
	// keep delivering safely during the grace, so there is no receive gap.
	retired atomic.Bool

	// sentCount is the number of chat frames written on this connection. The R7
	// server cuts an anonymous editor's socket with disconnectReason 4004
	// ("access deny") after a per-connection chat-message budget is spent —
	// measured live at ~160-220 messages, independent of size, so under load it
	// is reached in tens of seconds, before the ~45s time kick. writerLoop
	// rotates make-before-break once this reaches mailruRotateMsgs (well under
	// the budget), so the cut never happens. It also lets a drop be attributed
	// to the message budget (readLoop logs the count at the drop).
	sentCount atomic.Int64

	// active marks the one session whose reader delivers received frames.
	// Exactly one is active at a time, so a rotation never delivers a frame
	// twice (two readers delivering the same server broadcast would ACK it
	// twice, and the duplicate ACKs make the far side fast-retransmit). The new
	// connection reads from the moment it is dialed — to catch its auth and any
	// early frames — but only delivers once it is installed as active; the old
	// one stops delivering at that same instant. installSession sets it.
	active atomic.Bool
}

// markReady unblocks co-editing writes on this session. Safe to call more than
// once (the authChanges ack and the fallback timer may both fire).
func (s *DocSession) markReady() {
	s.readyOnce.Do(func() { close(s.ready) })
}

// markAuthed signals that the server accepted this connection's editor auth.
// Safe to call more than once.
func (s *DocSession) markAuthed() {
	s.authedOnce.Do(func() { close(s.authed) })
}

func (s *DocSession) safeWrite(messageType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	// A write into a half-open connection (NAT dropped it, the network
	// changed) would otherwise block until the kernel gives up, minutes.
	_ = s.Conn.SetWriteDeadline(time.Now().Add(docWriteTimeout))
	return s.Conn.WriteMessage(messageType, data)
}

// docWriteTimeout bounds one WebSocket write to the document.
const docWriteTimeout = 20 * time.Second

// authChangesAckMsg acknowledges the server's authChanges. A participant that
// advertised supportAuthChangesAck (our auth does) must reply with this after
// the server sends authChanges; otherwise the modern Mail.ru/R7 co-editing
// backend keeps the session un-ready and closes the socket (close 1005) on the
// first write. Captured from the live editor (docs.datacloudmail.ru,
// v2026.2.1.2268): the ack carries only its type, in our usual "message" envelope.
const authChangesAckMsg = `42["message",{"type":"authChangesAck"}]`

// authChangesReadyFallback is how long a session waits for the server's
// authChanges before releasing co-editing writes anyway, so a server that does
// not run the authChangesAck handshake is not stalled. A modern server sends
// authChanges within a round-trip, so this only matters for a legacy one.
const authChangesReadyFallback = 3 * time.Second

const mailruSocketIOHandshakeTimeout = 15 * time.Second

// waitMailruSocketIO completes the Engine.IO / Socket.IO handshake before
// editor authentication is sent. Mail.ru currently requires the Engine.IO
// open frame to be consumed before the Socket.IO connect packet is sent.
func waitMailruSocketIO(session *DocSession, token string) error {
	if session == nil || session.Conn == nil {
		return fmt.Errorf("mailru: websocket session is nil")
	}

	conn := session.Conn

	if err := conn.SetReadDeadline(time.Now().Add(mailruSocketIOHandshakeTimeout)); err != nil {
		return fmt.Errorf("mailru: set handshake deadline: %w", err)
	}
	defer conn.SetReadDeadline(time.Time{})

	waitFor := func(match func(string) bool) error {
		for {
			messageType, payload, err := conn.ReadMessage()
			if err != nil {
				return err
			}

			if messageType != websocket.TextMessage {
				continue
			}

			text := string(payload)

			// Engine.IO heartbeat may arrive while handshaking.
			if text == "2" {
				if err := session.safeWrite(websocket.TextMessage, []byte("3")); err != nil {
					return fmt.Errorf("mailru: send Engine.IO pong: %w", err)
				}
				continue
			}

			if match(text) {
				return nil
			}

			utils.Debugf("[M-DOCS] handshake: ignoring server frame %q", text)
		}
	}

	// Engine.IO open:
	//   0{"sid":"...", ...}
	if err := waitFor(func(text string) bool {
		return strings.HasPrefix(text, "0{")
	}); err != nil {
		return fmt.Errorf("mailru: wait for Engine.IO open: %w", err)
	}

	utils.Debugf("[M-DOCS] Engine.IO open")

	socketConnect := fmt.Sprintf(`40{"token":"%s"}`, token)

	if err := session.safeWrite(websocket.TextMessage, []byte(socketConnect)); err != nil {
		return fmt.Errorf("mailru: send Socket.IO connect: %w", err)
	}

	// Socket.IO acknowledgement:
	//   40{"sid":"..."}
	if err := waitFor(func(text string) bool {
		return strings.HasPrefix(text, "40")
	}); err != nil {
		return fmt.Errorf("mailru: wait for Socket.IO connect: %w", err)
	}

	utils.Debugf("[M-DOCS] Socket.IO connected")

	return nil
}

type MailruDocsTransport struct {
	*transport.BaseTransport

	weblink string
	session *DocSession

	// writeQueue is the one carrier write queue, shared by every session this
	// transport opens. It used to live on the live DocSession, so between the
	// drop of one connection and the install of the next Send failed with
	// "no active session" for the whole 1-3s reconnect — dropping exactly the
	// DNS replies and SYN-ACKs whose round trip was in flight and making a
	// browsing session fail at the name-resolution step. dialAndAuth reuses it,
	// so packets queued during the gap go out on the next connection.
	writeQueue chan []byte

	userCounter atomic.Int32
	baseUserID  string

	cookieJar *cookiejar.Jar
	jarMu     sync.RWMutex

	// reconnecting is set while a scheduled reconnect waits out its backoff:
	// the reader's error and ApplyCookies both schedule one when the cookies
	// are replaced under a live connection, and two reconnects open two
	// sessions to the document.
	reconnecting atomic.Bool

	// recvCh decouples the single WebSocket read goroutine from downstream
	// delivery. handleMessage runs on that goroutine; calling CallReceive
	// directly made a downstream stall under heavy receive load block the
	// reader, so the next Engine.IO ping went unread and unanswered and the R7
	// server dropped the socket at its ~45s ping timeout (close 1005). The
	// reader now pongs at once and hands data here; a worker drains it into
	// CallReceive. A full channel drops the packet (the Session's ARQ recovers
	// it) rather than stalling the reader.
	recvCh chan []byte

	// sessionStart is the UnixNano at which the current active session was
	// installed. rotationLoop uses it to rotate the carrier by connection age
	// (reset on every reconnect and rotation), so rotation stays aligned to the
	// live connection rather than to process start.
	sessionStart atomic.Int64

	// rotating guards against launching a second make-before-break rotation
	// while one is already in flight.
	rotating atomic.Bool

	// lastDial is the UnixNano at which this transport last started a carrier
	// dial. rotateCarrier refuses to dial again before mailruDialFloor has
	// passed since it: the editor API answers a too-frequent dial with http 429
	// and the transport then keeps sending on a connection whose budget is
	// already spent (measured on the phone 2026-11-07, see mailruDialFloor).
	lastDial atomic.Int64

	// ourIDs holds every anonymous editor id this transport has authenticated as
	// (one per carrier connection; the server assigns a fresh one each time).
	// The receive path skips any chat frame whose useridoriginal is in this set
	// — the server echoes our own frames back, and during a rotation's overlap
	// the old connection also sees the new connection's echoes. Without tracking
	// all of them, the old reader would re-deliver our own egress as ingress.
	ourIDs sync.Map

	// frameDedup drops a payload that arrives twice within a few hundred
	// milliseconds. During a make-before-break rotation both carrier
	// connections receive every broadcast, so without it the peer gets each
	// packet twice — measured on 2026-11-07: the exit saw 2.2x more packets
	// than the client sent, and the duplicate SYNs it produced confused the
	// TCP handshake. The window is deliberately short: a legitimate TCP
	// retransmission arrives after an RTO of a second or more and must pass.
	frameDedup *frameDedup

	// authedConn counts carrier connections whose auth the server accepted.
	// hasCookies is set by ApplyCookies: comparing the 4004-kick budget
	// (sentCount at kick time, logged in handleMessage) between anonymous and
	// authorized sessions tells whether cookie sessions get a bigger message
	// budget — right now rotation is tuned blind for both.
	authedConn  atomic.Int64
	hasCookies  atomic.Bool
	kickBudgets atomic.Int64 // kicks observed (for the Infof summary line)

	// kickCooldownUntil (UnixNano) delays the reconnect that follows an
	// anti-abuse 4004 kick. Reconnecting immediately after a kick is what
	// shrinks the budget on the next connection: the log shows the same
	// document dropping at 36s, then 13s, then 6s of age over consecutive
	// attempts, because each fresh socket starts with the bucket already
	// drained by the previous burst. A pause lets the bucket refill.
	kickCooldownUntil atomic.Int64

	// rateLimitUntil (UnixNano) delays the next dial after the carrier answered
	// a WebSocket upgrade with 429. Mail.ru meters how often one account may
	// open connections: a rotation dialled every few seconds starts coming back
	// as "websocket dial (http 429)", the rotation then fails, the old
	// connection is kicked on its frame budget, and the tunnel stalls for the
	// whole reconnect. Waiting out the limit instead of hammering it is what
	// keeps the carrier usable; rateLimitHits is logged so the stand can see it.
	rateLimitUntil atomic.Int64
	rateLimitHits  atomic.Int64
}

// mailruRecvQueue bounds the decoupling receive buffer (whole batches, so kept
// modest): it absorbs delivery jitter, and a genuine downstream stall fills it
// and drops rather than ever blocking the reader.
const mailruRecvQueue = 256

// NewMailruDocsTransport accepts either a bare weblink ("AbCdEfGh1/IjKlMnOp2")
// or a full public URL ("https://cloud.mail.ru/public/AbCdEfGh1/IjKlMnOp2"),
// normalizing the latter to the former.
// frameDedup remembers payloads seen in the last few hundred milliseconds so a
// broadcast delivered by two overlapping carrier connections is accepted once.
type frameDedup struct {
	mu   sync.Mutex
	seen map[uint64]time.Time
	ttl  time.Duration
}

func newFrameDedup(ttl time.Duration) *frameDedup {
	return &frameDedup{seen: make(map[uint64]time.Time), ttl: ttl}
}

// accept reports whether the payload is new, recording it either way.
func (d *frameDedup) accept(pkt []byte) bool {
	if d == nil {
		return true
	}
	var h uint64 = 1469598103934665603
	for _, b := range pkt {
		h ^= uint64(b)
		h *= 1099511628211
	}
	now := time.Now()
	d.mu.Lock()
	defer d.mu.Unlock()
	if at, ok := d.seen[h]; ok && now.Sub(at) < d.ttl {
		return false
	}
	if len(d.seen) > 512 {
		for k, at := range d.seen {
			if now.Sub(at) >= d.ttl {
				delete(d.seen, k)
			}
		}
		if len(d.seen) > 4096 {
			d.seen = make(map[uint64]time.Time)
		}
	}
	d.seen[h] = now
	return true
}

func NewMailruDocsTransport(weblink string, config transport.TransportConfig) *MailruDocsTransport {
	t := &MailruDocsTransport{
		BaseTransport: transport.NewBaseTransport(config),
		weblink:       normalizeWeblink(weblink),
	}
	t.baseUserID = randUserID()
	t.writeQueue = make(chan []byte, config.MaxQueueSize)
	jar, _ := cookiejar.New(nil)
	t.cookieJar = jar
	return t
}

func normalizeWeblink(weblink string) string {
	weblink = strings.TrimSpace(weblink)
	if idx := strings.IndexAny(weblink, "?#"); idx != -1 {
		weblink = weblink[:idx]
	}
	for _, prefix := range []string{
		"https://cloud.mail.ru/public/",
		"http://cloud.mail.ru/public/",
		"https://cloud.mail.ru/",
		"http://cloud.mail.ru/",
		"https://docs.mail.ru/public/",
		"http://docs.mail.ru/public/",
		"https://docs.mail.ru/d/",
		"http://docs.mail.ru/d/",
		"https://docs.mail.ru/",
		"http://docs.mail.ru/",
		"https://doc.mail.ru/public/",
		"http://doc.mail.ru/public/",
		"https://doc.mail.ru/d/",
		"http://doc.mail.ru/d/",
		"https://doc.mail.ru/",
		"http://doc.mail.ru/",
		"/public/",
		"public/",
	} {
		if strings.HasPrefix(weblink, prefix) {
			weblink = strings.TrimPrefix(weblink, prefix)
			break
		}
	}
	return strings.Trim(weblink, "/")
}

func (t *MailruDocsTransport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}

	// Drain received tunnel packets off the read goroutine so a slow downstream
	// never blocks the reader (and thus the Engine.IO pong). See recvCh.
	t.recvCh = make(chan []byte, mailruRecvQueue)
	t.frameDedup = newFrameDedup(250 * time.Millisecond)
	utils.SafeGo("mailru.recv", func() {
		for {
			select {
			case pkt := <-t.recvCh:
				t.CallReceive(pkt)
			case <-t.Done():
				return
			}
		}
	})

	t.baseUserID = randUserID()
	// Data rides the editor chat channel (see writerLoop/handleMessage). The old
	// cursor keepalive and saveChanges "editor activity" are gone: the current
	// Mail.ru server (R7 2026.2.1.2268) validates cursor/saveChanges against the
	// live document model and closes the socket ("access deny", code 4004) on any
	// such write. The Engine.IO ping/pong (handled in handleMessage) keeps the WS up.
	t.connectToDoc(0)

	// Preemptively rotate the carrier before the server's ~45s drop under load
	// (make-before-break, no tunnel gap). See rotationLoop / rotateCarrier.
	if mailruRotateInterval > 0 {
		utils.SafeGo("mailru.rotation", t.rotationLoop)
	}

	return nil
}

// mailruPreferredBatchBytes is the default coalescing batch size for the chat
// carrier. The R7 server cuts a connection with 4004 after ~22-34 chat messages
// regardless of their size, so a large batch fits far more tunnel data into
// that fixed message budget. Measured on 2026-11-07 against a live document
// (Windows client and Linux exit on separate hosts, downloads through socks5h):
//
//	500 KB batches — large transfers wedge: 1 MB stalled at ~220 KB
//	60 KB batches  — fast for pages (100 KB in 1.8 s), 1 MB stalled at 728 KB
//	32 KB batches  — 100 KB in 1.4 s, furthest progress on 1 MB
//	16 KB batches  — 1 MB completes, but throughput halves (21 KB/s)
//
// A chat message is base64 (+33%) inside a JSON text frame, so a batch stays
// under the frame size the server accepts while still carrying ~20 segments per
// message — which is what makes the ~25-message budget last over real traffic.
// 48 KB is the largest batch below the 60 KB point where a 1 MB transfer
// stalled, and it carries 50% more tunnel data per metered frame than 32 KB.
// OPENFLUX_BATCH_BYTES overrides this (see transport.BatchSizeHinter).
const mailruPreferredBatchBytes = 48_000

// PreferredBatchBytes implements transport.BatchSizeHinter.
func (t *MailruDocsTransport) PreferredBatchBytes() int { return mailruPreferredBatchBytes }

// mailruPreferredBatchLingerMs widens the coalescing window: the budget the
// carrier meters is messages, not bytes, and the 5 ms global default collects
// one or two segments at browsing rates. Measured: 25 ms cut a 145 KB news page
// from 17.5 s to 11.6 s and ya.ru from 2.8 s to 1.5 s, with no kicks.
// OPENFLUX_BATCH_LINGER_MS overrides it (see transport.BatchLingerHinter).
const mailruPreferredBatchLingerMs = 25

// PreferredBatchLingerMs implements transport.BatchLingerHinter.
func (t *MailruDocsTransport) PreferredBatchLingerMs() int { return mailruPreferredBatchLingerMs }

// defaultMailruDialFloorMs is the minimum gap between two carrier dials.
//
// This is the knob that actually sets the ceiling. The editor API meters dials
// the way the document meters chat frames: a dial 4 s after the previous one
// answers http 429, and the transport then sits out a ~10 s cooldown while it
// keeps writing on a connection whose 22-34 frame budget is already spent.
// Measured on the phone 2026-11-07 (Android client v1.7.43, exit @7): the frame
// trigger dialed every ~4 s, got "dial rate-limited (http 429, hit #1)" within
// seconds, then the exit logged "server disconnectReason (kick #18, sent=25)"
// and the tunnel went from ~1 KB/s to dead for 8-22 s. On the same stand with
// traffic slow enough for one dial per ~30 s there were 18 rotations, 0 kicks
// and 0 rate-limits over 9 minutes.
//
// Throughput is therefore (frames per connection x batch) / dial floor, and the
// send pacing below is derived from this value so one connection's frame budget
// exactly spans one dial window. OPENFLUX_MAILRU_DIAL_FLOOR_MS overrides it;
// 0 disables the floor (dial as soon as the frame budget is spent).
const defaultMailruDialFloorMs = 15_000

var mailruDialFloor = func() time.Duration {
	n := defaultMailruDialFloorMs
	if v := os.Getenv("OPENFLUX_MAILRU_DIAL_FLOOR_MS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil && parsed >= 0 {
			n = parsed
		}
	}
	return time.Duration(n) * time.Millisecond
}()

// defaultMailruRotateMsgs rotates the carrier make-before-break once this many
// chat frames have been written on it. The carrier cuts a connection after
// 22-34 frames regardless of pacing (measured 2026-11-07 over 22 harness runs),
// and a rotation only prevents that cut when
//
//	rotateMsgs + auth_frames < 22,  auth_frames = auth_time(~0.5-1.5 s)/interval
//
// so the count has to leave room for the dial to land: 16 frames plus the ~2
// frames a 1.5 s auth costs at the pacing below is 18, still under the budget,
// while the old 8 wasted two thirds of every connection. Rotation is also
// gated by mailruDialFloor, so raising this does not raise the dial rate.
// OPENFLUX_MAILRU_ROTATE_MSGS overrides it; 0 disables pre-emptive rotation and
// measured as the worst setting — the connection is always kicked and the
// tunnel then sits dead for 8-22 s.
const defaultMailruRotateMsgs = 16

var mailruRotateMsgs = func() int64 {
	n := int64(defaultMailruRotateMsgs)
	if v := os.Getenv("OPENFLUX_MAILRU_ROTATE_MSGS"); v != "" {
		if parsed, err := strconv.ParseInt(v, 10, 64); err == nil && parsed >= 0 {
			n = parsed
		}
	}
	return n
}()

// mailruHardFrameCap is the frame count a single connection must never reach:
// the server's 4004 budget is 22-34 frames, and a connection that reaches this
// one is already spending another connection's allowance. The writer stops
// handing it frames and waits for the swap instead — a short queue stall is
// recoverable (TCP retransmits), a 4004 kick costs 8-22 s of dead tunnel.
const mailruHardFrameCap = 20

// defaultMailruMsgIntervalMs is the minimum gap between chat writes on one
// carrier connection, and it is derived rather than fixed: one connection's
// frame budget must last exactly one dial window, otherwise the transport
// either dials faster than the carrier allows (http 429, see
// mailruDialFloor) or idles a healthy connection while that window runs out.
// With the defaults (15 s floor, 16 frames) that is ~940 ms per frame, giving
// ~51 KB/s of tunnel payload — the carrier's own ceiling, not ours.
//
// An earlier fixed 150 ms pacing looked better in isolation (577 B page in
// 0.3 s, ya.ru 1.5 s) but only because the harness dialed once per ~30 s and
// never met the dial meter; under real traffic it produced the 429 storm above.
// OPENFLUX_MAILRU_MSG_INTERVAL_MS overrides it; 0 disables pacing.
const defaultMailruMsgIntervalMs = 150

var mailruMsgInterval = func() time.Duration {
	if v := os.Getenv("OPENFLUX_MAILRU_MSG_INTERVAL_MS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil && parsed >= 0 {
			return time.Duration(parsed) * time.Millisecond
		}
	}
	if mailruDialFloor > 0 && mailruRotateMsgs > 0 {
		return mailruDialFloor / time.Duration(mailruRotateMsgs)
	}
	return defaultMailruMsgIntervalMs * time.Millisecond
}()

// defaultMailruRotateMs is the connection age at which the carrier WebSocket is
// preemptively rotated by the timer. 0 disables the timer: rotation is driven by
// the frame count together with mailruDialFloor, which is what the carrier
// meters. A 12 s timer fired late at high rate and its queue stall pushed the
// round trip to 1.6-3 s, which broke TCP handshakes on the client.
// OPENFLUX_MAILRU_ROTATE_MS overrides it; 0 disables the timer.
const defaultMailruRotateMs = 0

var mailruRotateInterval = func() time.Duration {
	n := defaultMailruRotateMs
	if v := os.Getenv("OPENFLUX_MAILRU_ROTATE_MS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil && parsed >= 0 {
			n = parsed
		}
	}
	return time.Duration(n) * time.Millisecond
}()

// mailruRotateAuthWait bounds how long a rotation waits for the server to accept
// the new connection's auth before swapping sends onto it.
const mailruRotateAuthWait = 8 * time.Second

func (t *MailruDocsTransport) Send(data []byte) error {
	// The queue outlives any single carrier connection, so a packet handed over
	// while the tunnel is between connections is kept rather than dropped; the
	// writer holds it until a ready session appears. Only a genuinely full
	// queue fails, and the caller's TCP/UDP layer retransmits that.
	select {
	case t.writeQueue <- data:
		t.RecordSend(len(data))
		return nil
	default:
		return fmt.Errorf("write queue full")
	}
}

func (t *MailruDocsTransport) connectToDoc(attempt int) {
	if !t.IsRunning() {
		return
	}

	utils.Debugf("[M-DOCS] connectToDoc attempt %d", attempt)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				utils.Debugf("[PANIC] recovered in mailru.connect: %v", r)
			}
		}()
		t.Mu.Lock()
		existingSession := t.session
		t.Mu.Unlock()

		var userID string
		var writeQueue chan []byte
		if existingSession != nil {
			// A reconnect keeps the editor identity and, crucially, the same
			// write queue, so packets already queued by Send are not lost.
			userID = existingSession.UserID
			writeQueue = existingSession.WriteQueue
		} else {
			suffix := fmt.Sprintf("%03d", t.userCounter.Add(1)%1000)
			userID = t.baseUserID + suffix
		}

		session, err := t.dialAndAuth(userID, writeQueue)
		if err != nil {
			utils.Debugf("[M-DOCS] connect failed: %v", err)
			if utils.Throttled("m-docs.fetch", time.Minute) {
				utils.Infof("[M-DOCS] cannot open the document: %v; retrying", err)
			}
			if errors.Is(err, errMailruAuthRequired) {
				// Retrying cannot fix missing or expired cookies, and the
				// backoff runs forever (MaxReconnectAttempts is effectively
				// unlimited). Stop and say why, instead of hammering the API
				// once a minute with a 401 nobody reads.
				utils.Infof("[M-DOCS] stopping: %v", err)
				_ = t.Stop()
				return
			}
			t.scheduleReconnect(attempt)
			return
		}

		firstConnect := existingSession == nil
		t.installSession(session)
		if firstConnect {
			utils.SafeGo("mailru.writer", t.writerLoop)
		}
		t.readLoop(session, attempt)
	}()
}

// dialAndAuth opens a fresh carrier WebSocket, completes the Engine.IO/Socket.IO
// handshake and sends the editor auth, returning a session whose read loop has
// not yet been started and which is not yet installed as the active session. It
// is the shared body of the first connect, a reconnect and a make-before-break
// rotation. userID is reused across reconnects/rotations (one editor identity);
// writeQueue, when non-nil, is reused so queued packets survive the swap. On any
// failure it leaves no connection open and returns the error; the caller decides
// whether to reconnect (connectToDoc) or keep the current carrier
// (rotateCarrier).
func (t *MailruDocsTransport) dialAndAuth(userID string, writeQueue chan []byte) (*DocSession, error) {
	info, err := t.fetchDocInfo(t.weblink)
	if err != nil {
		return nil, fmt.Errorf("fetchDocInfo: %w", err)
	}

	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()

	dialer := &websocket.Dialer{
		EnableCompression: true,
		HandshakeTimeout:  15 * time.Second,
		Jar:               jar,
	}
	utils.ConfigureWebsocketDialerProxy(dialer, info.WsURL)
	headers := http.Header{}
	headers.Set("User-Agent", mailruUserAgent)
	// Some Mail.ru edge nodes still answer 409 to the upgrade when the
	// request looks less like a browser than the others; a real browser
	// always sends Accept-Language. Reported to still hit iOS (#164).
	headers.Set("Accept-Language", "en-US,en;q=0.9")
	headers.Set("Origin", "https://docs.datacloudmail.ru")

	utils.Debugf("[M-DOCS] WebSocket dial %s", info.WsURL)
	// Stamp the dial whether or not it succeeds: the floor exists to keep the
	// carrier's dial meter from ever seeing a burst, and a failed dial counts
	// against that meter just like a successful one.
	t.lastDial.Store(time.Now().UnixNano())
	conn, resp, err := dialer.Dial(info.WsURL, headers)
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		if status == http.StatusTooManyRequests {
			// The carrier meters connection opens per account. Back off for
			// longer than the ordinary retry ladder would, and say so: without
			// this the rotation keeps dialling into the limit, the old
			// connection is kicked, and the tunnel sits dead for every retry.
			wait := mailruRateLimitWait()
			t.rateLimitUntil.Store(time.Now().Add(wait).UnixNano())
			n := t.rateLimitHits.Add(1)
			utils.Infof("[M-DOCS] dial rate-limited (http 429, hit #%d): waiting %v before the next attempt", n, wait.Round(time.Millisecond))
		}
		return nil, fmt.Errorf("websocket dial (http %d): %w", status, err)
	}
	utils.Debugf("[M-DOCS] WebSocket connected")

	// Every session writes from the transport's single queue so a rotation
	// carries the pending packets across instead of orphaning them.
	writeQueue = t.writeQueue

	session := &DocSession{
		Info:       info,
		Conn:       conn,
		WriteQueue: writeQueue,
		UserID:     userID,
		ready:      make(chan struct{}),
		authed:     make(chan struct{}),
	}

	// Remember this connection's editor id so the receive path can skip its
	// echoes — including on the old connection during a rotation's overlap.
	if info.EditorUserID != "" {
		t.ourIDs.Store(info.EditorUserID, struct{}{})
	}

	if err := waitMailruSocketIO(session, info.Token); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socket.io handshake: %w", err)
	}

	authMsg := map[string]interface{}{
		"type":                "auth",
		"docid":               info.DocKey,
		"documentCallbackUrl": info.CallbackURL,
		"token":               "fghhfgsjdgfjs",
		"user": map[string]interface{}{
			"id":        info.EditorUserID,
			"username":  userID,
			"indexUser": -1,
		},
		"editorType":         0,
		"lastOtherSaveTime":  -1,
		"block":              []interface{}{},
		"documentFormatSave": 65,
		"view":               false,
		"isCloseCoAuthoring": false,
		"openCmd": map[string]interface{}{
			"c":               "open",
			"id":              info.DocKey,
			"userid":          info.EditorUserID,
			"format":          info.FileType,
			"url":             info.DocURL,
			"title":           info.DocTitle,
			"lcid":            25,
			"nobase64":        true,
			"convertToOrigin": ".pdf.xps.oxps.djvu",
		},
		"lang":                  "ru",
		"mode":                  "edit",
		"permissions":           info.Permissions,
		"IsAnonymousUser":       false,
		"timezoneOffset":        -180,
		"coEditingMode":         "fast",
		"jwtOpen":               info.Token,
		"time":                  1000,
		"supportAuthChangesAck": true,
	}
	messagePart, _ := json.Marshal([]interface{}{"message", authMsg})
	session.safeWrite(websocket.TextMessage, []byte(fmt.Sprintf("42%s", string(messagePart))))

	// A modern Mail.ru/R7 server replies to this auth (which advertises
	// supportAuthChangesAck) with an authChanges message and only accepts
	// our writes after we ack it; handleMessage does that and marks the
	// session ready. Guard against an older server that never sends
	// authChanges: release the writes after a short grace period so the
	// carrier is not stalled forever waiting for an ack that never comes.
	time.AfterFunc(authChangesReadyFallback, session.markReady)

	return session, nil
}

// installSession makes session the active carrier and records when, so
// rotationLoop can rotate it by connection age. A rotation retires the old
// session before calling this.
func (t *MailruDocsTransport) installSession(session *DocSession) {
	session.active.Store(true)
	t.Mu.Lock()
	t.session = session
	t.SetConnected(true)
	t.Mu.Unlock()
	t.sessionStart.Store(time.Now().UnixNano())
}

// readLoop reads one carrier connection until it fails or the transport stops.
// On a read error it reconnects only if this session is still the active one and
// was not retired by a rotation (a retired connection's replacement is already
// live, so it exits silently, no reconnect). A retired session also stops
// delivering frames: its anonymous editor id differs from the new connection's,
// so it can no longer filter the new connection's own echoes out of the data.
func (t *MailruDocsTransport) readLoop(session *DocSession, attempt int) {
	conn := session.Conn
	connectedAt := time.Now()
	for t.IsRunning() {
		_, message, err := conn.ReadMessage()
		if err != nil {
			if session.retired.Load() {
				utils.Debugf("[M-DOCS] retired carrier closed: %v", err)
				return
			}
			utils.Debugf("[M-DOCS] Read error: %v", err)
			if utils.Throttled("m-docs.drop", time.Minute) {
				utils.Infof("[M-DOCS] connection to the document dropped after %d sends, age %s: %v; reconnecting",
					session.sentCount.Load(), time.Since(connectedAt).Round(time.Second), err)
			}
			t.Mu.Lock()
			stillActive := t.session == session
			if stillActive {
				t.session = nil
				t.SetConnected(false)
			}
			t.Mu.Unlock()
			_ = conn.Close()
			if stillActive {
				next := attempt
				if time.Since(connectedAt) > 15*time.Second {
					next = -1
				}
				t.scheduleReconnect(next)
			}
			return
		}
		if session.retired.Load() {
			continue
		}
		t.handleMessage(session, message)
	}
}

// rotationLoop preemptively rotates the carrier once the active connection
// reaches mailruRotateInterval, staying ahead of the R7 server's ~45s drop of an
// anonymous editor under load. It works off the live connection's age
// (sessionStart), so an ordinary reconnect resets the clock too.
func (t *MailruDocsTransport) rotationLoop() {
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-t.Done():
			return
		case <-tick.C:
			// Age-based rotation applies to authenticated sessions too: the
			// 4004 budget and the ~45 s time kick are per-connection, so a
			// cookie session gets cut just like an anonymous one.
			if mailruRotateInterval <= 0 {
				// Disabled: rotation is driven by the frame count instead (see
				// defaultMailruRotateMs). Without this guard the age test below
				// is always true for a 0 interval, so the transport dialed a
				// fresh carrier on every tick — a permanent rotation loop that
				// burned the server's per-connection budget and left the tunnel
				// in a reconnect gap most of the time.
				continue
			}
			if !t.IsRunning() || !t.IsConnected() {
				continue
			}
			start := t.sessionStart.Load()
			if start == 0 || time.Since(time.Unix(0, start)) < mailruRotateInterval {
				continue
			}
			t.Mu.RLock()
			s := t.session
			t.Mu.RUnlock()
			if s == nil {
				continue
			}
			if t.rotating.CompareAndSwap(false, true) {
				old := s
				utils.SafeGo("mailru.rotate", func() { t.rotateCarrier(old) })
			}
		}
	}
}

// rotateCarrier performs a make-before-break rotation with no visible seam: it
// brings up and authenticates a fresh carrier connection while old still carries
// traffic, starts reading it, and waits for the server to accept its auth before
// moving outgoing traffic onto it (so a packet is never sent on a connection the
// server will not yet relay — which would stall a whole RTO). At the swap the
// new connection becomes the delivering one and old is retired and closed at
// once: old keeps nothing in flight that new will not also receive (every chat
// is broadcast to all our connections), and having exactly one deliverer avoids
// the duplicate ACKs that would make the far side fast-retransmit. writerLoop
// and Send follow t.session and share the write queue, so no queued packet is
// lost. If the new connection cannot be established, old is left untouched and
// the normal drop/reconnect path remains the fallback; rotationLoop retries soon.
func (t *MailruDocsTransport) rotateCarrier(old *DocSession) {
	defer t.rotating.Store(false)
	if !t.IsRunning() {
		return
	}
	// Respect the carrier's dial meter before opening anything new. A fresh
	// connection spends its frame budget in ~15 s of traffic, so the trigger
	// can fire again within seconds; a dial inside the floor is answered with
	// http 429, which costs the current connection its remaining budget plus the
	// cooldown — the worst of both outcomes. Skipping is safe: the writer keeps
	// its pending packet and the trigger retries as soon as the floor passes.
	if wait := t.dialFloorRemaining(); wait > 0 {
		utils.Debugf("[M-DOCS] rotation deferred: %v left of the %v dial floor", wait.Round(time.Millisecond), mailruDialFloor)
		return
	}
	utils.Debugf("[M-DOCS] rotating carrier (age >= %v)", mailruRotateInterval)

	session, err := t.dialAndAuth(old.UserID, old.WriteQueue)
	if err != nil {
		utils.Debugf("[M-DOCS] rotation failed, keeping current carrier: %v", err)
		return
	}

	// Start reading the new connection so it processes its auth and receives
	// peer frames, but keep sending on old until the server has accepted the new
	// one. Both readers deliver during this window; the multi-identity echo
	// filter keeps either from re-delivering our own frames.
	utils.SafeGo("mailru.read", func() { t.readLoop(session, 0) })
	select {
	case <-session.authed:
	case <-time.After(mailruRotateAuthWait):
		// Do not swap into a connection the server has not accepted yet: every
		// chat frame written on it is refused, so the tunnel would go silent
		// until the Session ARQ timed out. Keeping the current carrier is
		// always better; if it dies the normal drop/reconnect path takes over
		// and rotationLoop tries again.
		utils.Debugf("[M-DOCS] rotation: new carrier not authed in %v; keeping current carrier", mailruRotateAuthWait)
		session.retired.Store(true)
		if session.Conn != nil {
			_ = session.Conn.Close()
		}
		return
	case <-t.Done():
		session.retired.Store(true)
		if session.Conn != nil {
			_ = session.Conn.Close()
		}
		return
	}

	// Swap: the new connection becomes active (and starts delivering) and old
	// stops delivering, in one step. Then retire and close old at once — new
	// already receives every broadcast, so there is nothing to wait for.
	old.active.Store(false)
	t.installSession(session)
	old.retired.Store(true)
	if old.Conn != nil {
		_ = old.Conn.Close()
	}
	t.RecordReconnect()
	utils.Debugf("[M-DOCS] carrier rotated")
}

// dialFloorRemaining reports how long rotateCarrier must still wait before it
// may open a new carrier connection. Zero means the meter is clear.
func (t *MailruDocsTransport) dialFloorRemaining() time.Duration {
	if mailruDialFloor <= 0 {
		return 0
	}
	last := t.lastDial.Load()
	if last == 0 {
		return 0
	}
	if remaining := mailruDialFloor - time.Since(time.Unix(0, last)); remaining > 0 {
		return remaining
	}
	return 0
}

func (t *MailruDocsTransport) writerLoop() {
	var queue chan []byte
	for t.IsRunning() && queue == nil {
		t.Mu.Lock()
		if t.session != nil {
			queue = t.session.WriteQueue
		}
		t.Mu.Unlock()
		if queue == nil {
			time.Sleep(5 * time.Millisecond)
		}
	}
	if queue == nil {
		return
	}

	var pending []byte
	var lastWrite time.Time
	for t.IsRunning() {
		if pending == nil {
			packet, ok := <-queue
			if !ok {
				return
			}
			pending = packet
		}

		t.Mu.RLock()
		session := t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil {
			time.Sleep(15 * time.Millisecond)
			continue
		}
		// The modern R7 backend accepts chat writes only after authChangesAck.
		// Keep the packet pending while this connection completes that handshake.
		select {
		case <-session.ready:
		case <-t.Done():
			return
		}

		// Pace the chat-message rate to flatten retransmit bursts so they do
		// not drain R7's 4004 message token bucket faster than a rotation can
		// swap in a fresh connection (see mailruMsgInterval / mailruRotateMsgs).
		// Backpressure propagates — a full WriteQueue makes Send fail and the
		// batcher holds the batch.
		if mailruMsgInterval > 0 && !lastWrite.IsZero() {
			if wait := mailruMsgInterval - time.Since(lastWrite); wait > 0 {
				time.Sleep(wait)
			}
		}

		// Tunnel data rides the document's chat channel: the server broadcasts a
		// chat "message" verbatim to every other participant and does not validate
		// its text against the document model (unlike cursor/saveChanges, which it
		// now rejects). We tag the base64 payload so our frames are told apart from
		// a human's chat and from the server's echo of our own messages.
		payload := chatDataTagV2 + t.baseUserID + base64.StdEncoding.EncodeToString(pending)
		msg := fmt.Sprintf(`42["message",{"type":"message","message":%q}]`, payload)
		if err := session.safeWrite(websocket.TextMessage, []byte(msg)); err != nil {
			utils.Debugf("[M-DOCS] Write error: %v", err)
			t.Mu.Lock()
			if t.session == session {
				t.session = nil
				t.SetConnected(false)
				t.scheduleReconnect(0)
			}
			t.Mu.Unlock()
			time.Sleep(50 * time.Millisecond)
			continue
		}
		lastWrite = time.Now()
		pending = nil

		// Rotate make-before-break once this connection has sent its share of
		// the server's per-connection message budget, before the server cuts it.
		// This applies to authenticated sessions too: the 4004 budget is
		// per-connection, not per-account, so skipping rotation for cookie
		// sessions (as this fork did in v1.0.19) let a loaded connection burn
		// the budget and get cut every ~15-35 s.
		//
		// The test is >= rather than == so a rotation that the dial floor
		// deferred is retried on the next frame instead of never happening.
		if n := session.sentCount.Add(1); mailruRotateMsgs > 0 && n >= mailruRotateMsgs {
			if t.rotating.CompareAndSwap(false, true) {
				s := session
				utils.SafeGo("mailru.rotate", func() { t.rotateCarrier(s) })
			}
			// Never write past the hard cap: the next frame on this connection
			// would spend another connection's allowance and earn a 4004 kick,
			// which costs an 8-22 s dead tunnel. Hold the pending packet until
			// the swap lands instead — a short stall is recovered by TCP.
			if mailruHardFrameCap > 0 && n >= mailruHardFrameCap {
				t.waitForCarrierSwap(session)
			}
		}
	}
}

// waitForCarrierSwap blocks until the transport is writing on a connection other
// than spent (or the transport stops), re-arming the rotation while it waits.
func (t *MailruDocsTransport) waitForCarrierSwap(spent *DocSession) {
	for t.IsRunning() {
		t.Mu.RLock()
		current := t.session
		t.Mu.RUnlock()
		if current != spent {
			return
		}
		if t.rotating.CompareAndSwap(false, true) {
			utils.SafeGo("mailru.rotate", func() { t.rotateCarrier(spent) })
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func (t *MailruDocsTransport) keepAliveLoop() {
	// Disabled 2026-10-15: the application keepalive wrote a cursor message
	// (`18;---KA---`) on the cursor channel, which the current R7 server
	// rejects with 4004 ("access deny") and tears the whole connection down —
	// this is what made Mail.ru fall over after a while even on a single lane.
	// The engine.io ping/pong keeps the WebSocket alive on its own and an
	// active tunnel is never idle, so no cursor keepalive is needed. Kept as a
	// no-op so the Start() call site stays unchanged; a proper liveness probe
	// (engine.io ping or an OFX1 chat no-op) can replace it later if idle
	// sessions turn out to need one.
	return
}

// unlockDocumentMsg releases the lock a joining editor puts on this one.
const unlockDocumentMsg = `42["message",{"type":"unLockDocument","unlock":true,"isSave":false,"releaseLocks":false,"deleteIndex":-1}]`

// editorActivityLoop периодически отправляет в документ saveChanges-сообщение
// в формате веб-клиента Mail.ru: значения UserId/UserShortId подставляются
// из текущей сессии, остальное — фиксированный шаблон (см.
// saveChangesMessageTemplate). Задержка между отправками — случайная в
// диапазоне [0.5 с, 5 с], чтобы поток выглядел как правки живого человека:
// ровный по сути, но не метрономом. Сообщение уходит через ту же сессию, что
// writerLoop и keepAliveLoop, и так же терпит переподключение — если сессии
// сейчас нет, итерация просто пропускается.
func (t *MailruDocsTransport) editorActivityLoop() {
	const (
		minDelay = 500 * time.Millisecond
		maxDelay = 5 * time.Second
	)
	for t.IsRunning() {
		span := int64(maxDelay - minDelay)
		delay := minDelay + time.Duration(rand.Int63n(span+1))

		select {
		case <-time.After(delay):
		case <-t.Done():
			return
		}

		t.Mu.RLock()
		session := t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil {
			continue
		}

		// Skip until co-editing is ready (authChanges acked / fallback fired),
		// so saveChanges never goes out before the server accepts our writes.
		select {
		case <-session.ready:
		default:
			continue
		}

		// A link that only lets the document be read ("edit": false) is not allowed to save: the
		// server closes the connection (no close frame) on the first saveChanges, over and over.
		if !canEdit(session.Info.Permissions) {
			continue
		}

		msg := buildSaveChanges(session)
		if err := session.safeWrite(websocket.TextMessage, msg); err != nil {
			utils.Debugf("[M-DOCS] saveChanges write error: %v", err)
		}
	}
}

// canEdit says whether the document may be edited through the link this session joined with.
// The server reports it in the document's permissions; a document that does not say is treated
// as editable, as the transport always did.
func canEdit(permissions map[string]interface{}) bool {
	edit, ok := permissions["edit"].(bool)
	return !ok || edit
}

// buildSaveChanges собирает saveChanges-сообщение под конкретную сессию:
// UserId — настоящий id участника (info.EditorUserID, при отсутствии —
// сгенерированный session.UserID), UserShortId — производная от UserId
// (в веб-клиенте Mail.ru это UserId без последнего символа). Остальное — из
// шаблона.
func buildSaveChanges(session *DocSession) []byte {
	userID := session.Info.EditorUserID
	if userID == "" {
		userID = session.UserID
	}
	return BuildSaveChanges(userID)
}

// BuildSaveChanges is the saveChanges message for one participant: a pure
// function of the user id, exported so the JS port of this transport can be
// compared with it byte for byte.
func BuildSaveChanges(userID string) []byte {
	short := userID
	if len(short) > 1 {
		short = short[:len(short)-1]
	}
	return []byte(fmt.Sprintf(saveChangesMessageTemplate, userID, short))
}

func (t *MailruDocsTransport) handleMessage(session *DocSession, data []byte) {
	text := string(data)

	if text == "2" {
		if session != nil && session.Conn != nil {
			session.safeWrite(websocket.TextMessage, []byte("3"))
		}
		return
	}
	if text == "3" {
		return
	}

	if strings.Contains(text, `"type":"auth"`) && strings.Contains(text, `"result":1`) {
		n := t.authedConn.Add(1)
		utils.Debugf("[M-DOCS] Auth OK for user %s (conn #%d, cookies=%v)", session.UserID, n, t.hasCookies.Load())
		// The server has accepted this connection: it will now relay our chat.
		// A rotation waits on this before moving sends onto the new connection.
		if session != nil {
			session.markAuthed()
		}
		return
	}

	if strings.Contains(text, `"type":"authChanges"`) {
		if session != nil && session.Conn != nil {
			if err := session.safeWrite(websocket.TextMessage, []byte(authChangesAckMsg)); err != nil {
				utils.Debugf("[M-DOCS] authChangesAck write error: %v", err)
				return
			}
			utils.Debugf("[M-DOCS] authChanges received and acked (co-editing ready)")
			session.markReady()
		}
		return
	}

	// The server announces why it is about to close us with a disconnectReason
	// frame (e.g. code 4004 "access deny" anti-abuse, 4007 "drop" lock
	// contention) just before the socket closes. Surface it at INFO — it is rare
	// and it is the one line that explains an otherwise bare "close 1005".
	// The sent-count at kick time is the measured 4004 budget: compare
	// anonymous vs cookie sessions to see if authorized users get more room.
	if strings.Contains(text, "disconnectReason") {
		n := t.kickBudgets.Add(1)
		var sent int64
		if session != nil {
			sent = session.sentCount.Load()
		}
		utils.Infof("[M-DOCS] server disconnectReason (kick #%d, cookies=%v, sent=%d): %.200s",
			n, t.hasCookies.Load(), sent, text)
		// 4004 is the anti-abuse message bucket. A cooldown was tried here to let
		// the bucket refill before reconnecting; it made no observable difference
		// in the field and cost a dead window on every spurious kick, so the
		// transport reconnects on the ordinary backoff like upstream does.
		// mailruKickCooldown stays as the knob to turn it back on.
		if mailruKickCooldown > 0 && strings.Contains(text, "4004") {
			t.kickCooldownUntil.Store(time.Now().Add(mailruKickCooldown).UnixNano())
			utils.Infof("[M-DOCS] 4004 anti-abuse kick; pausing %v before reconnecting", mailruKickCooldown)
		}
		return
	}

	if !strings.Contains(text, "cursor") && !strings.Contains(text, `"messages"`) {
		utils.Debugf("[M-DOCS] server message: %.300s", text)
	}

	// A second editor joining locks the document on the first one
	// (lockDocument) until that one unlocks it; the server drops a holder
	// that does not within 30s (disconnectReason 4007) and the dropped
	// peer, rejoining, locks it on the other: the carrier went down every
	// half minute while both peers were on it.
	if strings.Contains(text, `"type":"connectState"`) {
		if session != nil && session.Conn != nil {
			session.safeWrite(websocket.TextMessage, []byte(unlockDocumentMsg))
		}
		return
	}

	// Tunnel data arrives as a chat broadcast the server relays from the other
	// participant:
	//   42["message",{"type":"message","messages":[{message,useridoriginal,…}]}]
	// V2 frames carry a per-transport sender id, so endpoints authenticated with
	// the same Mail.ru account do not mistake each other for self echoes.
	// Only the active session delivers, so a frame is never delivered twice
	// during a rotation (both connections see every broadcast).
	if strings.Contains(text, `"messages"`) && session.active.Load() {
		for _, pkt := range decodeChatDataFilter(data, t.baseUserID, t.isOwnID) {
			if !t.frameDedup.accept(pkt) {
				continue
			}
			t.RecordReceive(len(pkt))
			// Never block the reader: a full queue means downstream is behind,
			// so drop (the Session's ARQ retransmits) and keep reading so the
			// Engine.IO ping is answered and the carrier is not dropped at the
			// ~45s ping timeout.
			select {
			case t.recvCh <- pkt:
			default:
				if utils.Throttled("m-docs.recvdrop", time.Second) {
					utils.Debugf("[M-DOCS] recv queue full; dropping packet (downstream behind)")
				}
			}
		}
	}
}

// V1 identified self echoes by Mail.ru's useridoriginal, which is shared when
// both endpoints use cookies from the same account. V2 embeds a random
// per-transport sender id and therefore distinguishes those endpoints.
const (
	chatDataTag        = "OFX1"
	chatDataTagV2      = "OFX2"
	chatSenderIDLength = 10
)

// isOwnID reports whether an editor id is one of this transport's own
// connections (so a chat frame from it is our own echo, not peer data).
func (t *MailruDocsTransport) isOwnID(id string) bool {
	if id == "" {
		return false
	}
	_, ok := t.ourIDs.Load(id)
	return ok
}

// decodeChatDataFilter extracts tunnel packets from a server chat broadcast
//
//	42["message",{"type":"message","messages":[{"message":"…","useridoriginal":"…"},…]}]
//
// V2 echoes are filtered by the sender id embedded in the payload. V1 remains
// readable and falls back to useridoriginal filtering for rolling upgrades.
func decodeChatDataFilter(frame []byte, ownSenderID string, isOwnLegacy func(string) bool) [][]byte {
	i := bytes.IndexByte(frame, '[')
	if i < 0 {
		return nil
	}
	var arr []json.RawMessage
	if err := json.Unmarshal(frame[i:], &arr); err != nil || len(arr) < 2 {
		return nil
	}
	var payload struct {
		Type     string `json:"type"`
		Messages []struct {
			Message        string `json:"message"`
			UserIDOriginal string `json:"useridoriginal"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(arr[1], &payload); err != nil || payload.Type != "message" {
		return nil
	}
	var out [][]byte
	for _, m := range payload.Messages {
		encoded := ""
		switch {
		case strings.HasPrefix(m.Message, chatDataTagV2):
			body := m.Message[len(chatDataTagV2):]
			if len(body) < chatSenderIDLength {
				continue
			}
			senderID := body[:chatSenderIDLength]
			if ownSenderID != "" && senderID == ownSenderID {
				continue
			}
			encoded = body[chatSenderIDLength:]
		case strings.HasPrefix(m.Message, chatDataTag):
			if isOwnLegacy != nil && isOwnLegacy(m.UserIDOriginal) {
				continue
			}
			encoded = m.Message[len(chatDataTag):]
		default:
			continue
		}
		dec, err := base64.StdEncoding.DecodeString(encoded)
		if err != nil {
			utils.Debugf("[M-DOCS] chat payload decode error: %v", err)
			continue
		}
		out = append(out, dec)
	}
	return out
}

// decodeChatData keeps only tagged entries from a single other participant
// (useridoriginal != ownID). Retained for the parity test / exported form.
func decodeChatData(frame []byte, ownID string) [][]byte {
	return decodeChatDataFilter(frame, "", func(id string) bool { return ownID != "" && id == ownID })
}

// DecodeChatData is the exported form of decodeChatData (pure; the JS port is
// compared with it in transport/script/parity_test.go).
func DecodeChatData(frame []byte, ownID string) [][]byte { return decodeChatData(frame, ownID) }

// cursorPayloads returns the base64 payload of every cursor entry in a server
// message, in order, without the keep-alive entries.
func cursorPayloads(text string) []string { return CursorPayloads(text) }

// CursorPayloads is the exported form of cursorPayloads (pure; the JS port is
// compared with it in tests).
func CursorPayloads(text string) []string {
	var out []string
	for _, m := range cursorPayloadRe.FindAllStringSubmatch(text, -1) {
		if len(m) > 1 && m[1] != "---KA---" {
			out = append(out, m[1])
		}
	}
	return out
}

func (t *MailruDocsTransport) scheduleReconnect(attempt int) {
	next := attempt + 1
	if !t.IsRunning() || next >= t.GetConfig().MaxReconnectAttempts {
		return
	}

	if !t.reconnecting.CompareAndSwap(false, true) {
		return // one is already waiting
	}
	d := reconnectBackoff(next)
	// A 4004 kick drains the per-connection budget; the backoff alone (0.5-15 s)
	// is shorter than the refill, so honour the cooldown on top of it.
	if until := t.kickCooldownUntil.Load(); until != 0 {
		if wait := time.Until(time.Unix(0, until)); wait > d {
			d = wait
		}
	}
	// A 429 means the account is being metered on connection opens: hammering it
	// costs us the carrier, so its wait wins over the ordinary backoff too.
	if until := t.rateLimitUntil.Load(); until != 0 {
		if wait := time.Until(time.Unix(0, until)); wait > d {
			d = wait
		}
	}
	utils.Debugf("[M-DOCS] reconnecting in %v (attempt %d)", d, next)
	time.Sleep(d)
	t.reconnecting.Store(false) // a failed connect schedules the next one itself
	if !t.IsRunning() {
		return
	}

	t.RecordReconnect()
	t.connectToDoc(next)
}

// mailruKickCooldown is how long the transport waits after an anti-abuse 4004
// kick before opening a new carrier connection.
//
// 0 (disabled) matches upstream, which reconnects on the ordinary backoff. The
// cooldown was added on the theory that an immediate reconnect lands on a
// budget the kick already drained; it made no observable difference in the
// field, and a 30s dead window is a real cost when the kick was spurious.
const mailruKickCooldown = 0 * time.Second

// mailruRateLimitBackoff is how long the transport waits after the carrier
// answers a WebSocket upgrade with 429 (Too Many Requests). Mail.ru meters how
// often one account may open connections; a rotation dialled every few seconds
// lands in that meter. Ten seconds plus jitter is long enough to sit the window
// out and short enough that the tunnel does not look dead.
const mailruRateLimitBackoff = 10 * time.Second

func mailruRateLimitWait() time.Duration {
	return mailruRateLimitBackoff + time.Duration(rand.Int63n(int64(mailruRateLimitBackoff/2)+1))
}

// reconnectBackoff returns an exponential backoff with jitter, capped at 15s.
func reconnectBackoff(n int) time.Duration {
	if n < 1 {
		n = 1
	}
	shift := n - 1
	if shift > 5 {
		shift = 5
	}
	d := 500 * time.Millisecond * time.Duration(1<<uint(shift))
	if d > 15*time.Second {
		d = 15 * time.Second
	}
	d += time.Duration(rand.Int63n(int64(d/2) + 1))
	return d
}

func fetchMailruCSRFToken(client *http.Client, weblink string) string {
	// 1. Try GET https://cloud.mail.ru/api/v2/tokens/csrf
	req, err := http.NewRequest("GET", "https://cloud.mail.ru/api/v2/tokens/csrf", nil)
	if err == nil {
		req.Header.Set("User-Agent", mailruUserAgent)
		req.Header.Set("Accept", "application/json, text/plain, */*")
		req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s", weblink))
		if resp, err := client.Do(req); err == nil {
			defer resp.Body.Close()
			if resp.StatusCode == 200 {
				var data struct {
					Body struct {
						Token string `json:"token"`
					} `json:"body"`
				}
				if json.NewDecoder(resp.Body).Decode(&data) == nil && data.Body.Token != "" {
					return data.Body.Token
				}
			}
		}
	}

	// 2. Try POST https://cloud.mail.ru/api/v2/tokens/csrf
	reqPost, err := http.NewRequest("POST", "https://cloud.mail.ru/api/v2/tokens/csrf", nil)
	if err == nil {
		reqPost.Header.Set("User-Agent", mailruUserAgent)
		reqPost.Header.Set("Accept", "application/json, text/plain, */*")
		reqPost.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s", weblink))
		if resp, err := client.Do(reqPost); err == nil {
			defer resp.Body.Close()
			if resp.StatusCode == 200 {
				var data struct {
					Body struct {
						Token string `json:"token"`
					} `json:"body"`
				}
				if json.NewDecoder(resp.Body).Decode(&data) == nil && data.Body.Token != "" {
					return data.Body.Token
				}
			}
		}
	}

	// 3. Fallback: GET public page HTML and extract tokens.csrf
	pageURL := fmt.Sprintf("https://cloud.mail.ru/public/%s", weblink)
	reqPage, err := http.NewRequest("GET", pageURL, nil)
	if err == nil {
		reqPage.Header.Set("User-Agent", mailruUserAgent)
		reqPage.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		if resp, err := client.Do(reqPage); err == nil {
			defer resp.Body.Close()
			body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
			re := regexp.MustCompile(`"tokens"\s*:\s*\{\s*"csrf"\s*:\s*"([^"]+)"`)
			if m := re.FindSubmatch(body); len(m) > 1 && len(m[1]) > 0 {
				return string(m[1])
			}
			reAlt := regexp.MustCompile(`"csrf"\s*:\s*"([^"]+)"`)
			if m := reAlt.FindSubmatch(body); len(m) > 1 && len(m[1]) > 0 {
				return string(m[1])
			}
		}
	}
	return ""
}

// fetchDocInfo POSTs to Mail.ru's public-document editor API and parses the
// response into the fields needed to open the collaborative WebSocket.
func (t *MailruDocsTransport) fetchDocInfo(weblink string) (MailruDocsInfo, error) {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	if jar == nil {
		var err error
		jar, err = cookiejar.New(nil)
		if err != nil {
			return MailruDocsInfo{}, err
		}
	}
	client := &http.Client{
		Jar:       jar,
		Transport: utils.GetSignalingHTTPTransport(),
		Timeout:   15 * time.Second,
	}

	csrfToken := fetchMailruCSRFToken(client, weblink)
	if csrfToken != "" {
		utils.Debugf("[M-DOCS] obtained Mail.ru CSRF token (%d bytes)", len(csrfToken))
	}

	reqBody := map[string]string{
		"x-email":  "anonym",
		"public":   "/" + weblink,
		"platform": "desktop_web",
	}
	if csrfToken != "" {
		reqBody["token"] = csrfToken
	}
	jsonData, _ := json.Marshal(reqBody)

	apiURL := "https://cloud.mail.ru/api/v4/r7/edit"
	if csrfToken != "" {
		apiURL = fmt.Sprintf("https://cloud.mail.ru/api/v4/r7/edit?token=%s", url.QueryEscape(csrfToken))
	}
	utils.Debugf("[M-DOCS] fetchDocInfo POST %s", apiURL)

	req, _ := http.NewRequest("POST", apiURL, bytes.NewBuffer(jsonData))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("User-Agent", mailruUserAgent)
	req.Header.Set("X-Api-Version", "4")
	req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s?weblink=%s", weblink, weblink))
	if csrfToken != "" {
		req.Header.Set("X-CSRF-Token", csrfToken)
	}

	resp, err := client.Do(req)
	if err == nil && resp.StatusCode != http.StatusOK && csrfToken != "" {
		// Retry without "x-email": "anonym" for authenticated requests
		resp.Body.Close()
		delete(reqBody, "x-email")
		jsonDataRetry, _ := json.Marshal(reqBody)
		reqRetry, _ := http.NewRequest("POST", apiURL, bytes.NewBuffer(jsonDataRetry))
		reqRetry.Header = req.Header.Clone()
		resp, err = client.Do(reqRetry)
	}
	if err != nil {
		return MailruDocsInfo{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		bodyBytes, _ := io.ReadAll(resp.Body)
		bodySnippet := string(bodyBytes)
		if len(bodySnippet) > 500 {
			bodySnippet = bodySnippet[:500]
		}
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return MailruDocsInfo{}, fmt.Errorf("%w (status %d: %s)", errMailruAuthRequired, resp.StatusCode, bodySnippet)
		}
		return MailruDocsInfo{}, fmt.Errorf("API returned status %d: %s", resp.StatusCode, bodySnippet)
	}

	bodyBytes, _ := io.ReadAll(resp.Body)

	var res map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &res); err != nil {
		return MailruDocsInfo{}, fmt.Errorf("failed to parse JSON: %w", err)
	}

	apiBase, _ := res["api"].(string)
	token, _ := res["token"].(string)

	document, ok := res["document"].(map[string]interface{})
	if !ok || document == nil {
		return MailruDocsInfo{}, fmt.Errorf("document object missing")
	}

	docKey, _ := document["key"].(string)
	fileType, _ := document["fileType"].(string)
	docURL, _ := document["url"].(string)
	docTitle, _ := document["title"].(string)
	permissions, _ := document["permissions"].(map[string]interface{})
	if permissions == nil {
		permissions = make(map[string]interface{})
	}

	editorConfig, ok := res["editorConfig"].(map[string]interface{})
	if !ok || editorConfig == nil {
		return MailruDocsInfo{}, fmt.Errorf("editorConfig object missing")
	}
	callbackURL, _ := editorConfig["callbackUrl"].(string)

	userObj, _ := editorConfig["user"].(map[string]interface{})
	var editorUserID string
	if userObj != nil {
		editorUserID, _ = userObj["id"].(string)
	}

	wsBase := strings.Replace(apiBase, "https://", "wss://", 1)
	wsURL := fmt.Sprintf("%s/doc/%s/c/?EIO=4&transport=websocket", wsBase, docKey)

	return MailruDocsInfo{
		Token:        token,
		DocKey:       docKey,
		WsURL:        wsURL,
		FileType:     fileType,
		DocURL:       docURL,
		DocTitle:     docTitle,
		Permissions:  permissions,
		CallbackURL:  callbackURL,
		EditorUserID: editorUserID,
	}, nil
}

func randUserID() string {
	return fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
}

// ---- CookieExchanger ----

// FetchCookies returns a snapshot of the transport's current cookie jar as
// name -> value. Used by the exit node to answer a SubtypeCookiesRequest.
func (t *MailruDocsTransport) FetchCookies() (map[string]string, error) {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	if jar == nil {
		return nil, fmt.Errorf("mailru: cookie jar is nil")
	}
	u, err := url.Parse("https://cloud.mail.ru/")
	if err != nil {
		return nil, err
	}
	out := make(map[string]string)
	for _, c := range jar.Cookies(u) {
		out[c.Name] = c.Value
	}
	return out, nil
}

// ApplyCookies replaces the transport's cookie jar with the provided values
// and forces the current session to reconnect.
func (t *MailruDocsTransport) ApplyCookies(values map[string]string) error {
	if len(values) == 0 {
		return nil
	}
	jar, _ := cookiejar.New(nil)
	mailruCookies := make([]*http.Cookie, 0, len(values))
	datacloudCookies := make([]*http.Cookie, 0, len(values))
	for k, v := range values {
		mailruCookies = append(mailruCookies, &http.Cookie{Name: k, Value: v, Path: "/", Domain: "mail.ru"})
		datacloudCookies = append(datacloudCookies, &http.Cookie{Name: k, Value: v, Path: "/", Domain: "datacloudmail.ru"})
	}
	for _, rawURL := range []string{"https://mail.ru/", "https://cloud.mail.ru/", "https://docs.mail.ru/"} {
		if u, err := url.Parse(rawURL); err == nil {
			jar.SetCookies(u, mailruCookies)
		}
	}
	if u, err := url.Parse("https://docs.datacloudmail.ru/"); err == nil {
		jar.SetCookies(u, datacloudCookies)
	}

	t.jarMu.Lock()
	t.cookieJar = jar
	t.jarMu.Unlock()
	t.hasCookies.Store(true)

	utils.Debugf("[M-DOCS] applied %d cookies, forcing reconnect", len(values))

	t.Mu.Lock()
	session := t.session
	t.session = nil
	t.SetConnected(false)
	t.Mu.Unlock()
	if session != nil && session.Conn != nil {
		_ = session.Conn.Close()
	}
	if t.IsRunning() {
		t.scheduleReconnect(0)
	}
	return nil
}

// LoadCookieFile imports a Netscape cookies.txt (mail.ru cookies)
// into the transport's cookie jar, so it opens the document signed in.
func (t *MailruDocsTransport) LoadCookieFile(path string) error {
	t.jarMu.Lock()
	if t.cookieJar == nil {
		jar, _ := cookiejar.New(nil)
		t.cookieJar = jar
	}
	jar := t.cookieJar
	t.jarMu.Unlock()

	if err := loadMailruCookies(path, jar); err != nil {
		return err
	}
	t.hasCookies.Store(true)
	utils.Debugf("[M-DOCS] cookie file loaded from %s", path)
	if t.IsConnected() && t.IsRunning() {
		t.Mu.Lock()
		session := t.session
		t.session = nil
		t.SetConnected(false)
		t.Mu.Unlock()
		if session != nil && session.Conn != nil {
			_ = session.Conn.Close()
		}
		t.scheduleReconnect(0)
	}
	return nil
}

