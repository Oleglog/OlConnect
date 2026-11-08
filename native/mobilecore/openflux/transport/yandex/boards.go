package yandex

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	mrand "math/rand"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"

	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/utils"
)

const (
	boardsBase = "boards.yandex.ru"
	boardsUA   = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"
	boardsSocketHostDefault = "socket33.boards.yandex.ru"

	// Heartbeat. Сервер шлёт engine.io ping "2" (pingInterval=25000,
	// pingTimeout=30000), мы отвечаем "3". Сами шлём только heartbeat в
	// namespace dashboard каждые 20с, чтобы NAT не рвал idle-соединение;
	// клиентский "2" сервер EIO=4 считает ошибкой и закрывает сокет.
	boardsPingInterval = 20 * time.Second

	// Дедлайн чтения в основном цикле. С запасом над boardsPingInterval.
	boardsReadDeadline = 90 * time.Second

	// Таймаут ожидания 431 в handshake.
	boardsHandshakeWait = 15 * time.Second

	// Потолок одного объекта доски. Сервер отвечает
	// {"error":{"code":200,"securityCode":"PAYLOAD_TOO_LARGE"}} на value
	// длиннее 65536 символов base64, то есть на payload больше 48 КиБ
	// (измерено 2026-11-08: 49152 байта проходят, 49153 уже нет).
	boardsMaxObjectBytes = 48 * 1024

	// Батч под этот потолок с запасом на обвязку объекта.
	boardsBatchBytes = 48000

	// securityCode of the refusals we act on. The board answers a refused
	// modify-objects with {"error":{"code":200,"securityCode":...}}.
	boardsRateLimited      = "RATE_LIMITED"
	boardsNetworkTransport = "NETWORK_TRANSPORT"

	// boardsRate* pace the writes. The board refuses modify-objects with
	// RATE_LIMITED when objects arrive too fast, and the exact budget is not
	// documented (2026-11-08: tens of objects per second were refused while
	// ~641 KB/s of payload still went through), so it is learned with AIMD
	// rather than guessed: start at boardsRateStart, add boardsRateGrow per
	// acknowledged object up to boardsRateMax, halve on RATE_LIMITED and keep
	// the next slot back for boardsRateHold. The floor is low on purpose: at
	// the floor a 48 KiB object still moves ~96 KB/s, degraded but alive.
	boardsRateStart = 5.0
	boardsRateMin   = 2.0
	boardsRateMax   = 18.0
	boardsRateGrow  = 0.5
	boardsRateCut   = 0.5
	boardsRateHold  = 250 * time.Millisecond
	// boardsRateHoldMax is where the hold stops doubling: at the floor the rate
	// cannot fall further, so a board whose budget is below the floor is met with
	// one object a second instead of a refusal storm. The stall count resets on
	// the first acknowledged object, so a refusal after a good object is held
	// back by boardsRateHold again and recovery is not slowed down.
	boardsRateHoldMax = time.Second

	// boardsMaxTries bounds a retry: a refusal that never clears must not pin
	// one object in the writer forever.
	boardsMaxTries = 16

	// boardsAckTimeout forgets an object whose ack never came. A healthy ack
	// arrives in 0.07-0.17 s; 15 s of silence means the board dropped the event
	// or the ack was lost, and holding it would leak 48 KiB per object.
	boardsAckTimeout = 15 * time.Second

	// boardsMaxPending bounds the objects in flight. The count is bounded and so
	// is the payload: 200 writes of 48 KiB would be ~9 MiB, and the same code
	// runs inside the phone, so the byte cap is what actually binds.
	boardsMaxPending      = 200
	boardsMaxPendingBytes = 2 << 20

	// Deletions are coalesced: one drop-objects per window carrying every object
	// that arrived in it. The old path spent one event per received object from
	// the same per-second budget as the payload.
	boardsDropWindow = 300 * time.Millisecond
	// boardsDropEventBytes caps the marshalled size of one drop-objects event.
	// The board's measured ceiling is 48 KiB of decoded value per object, and a
	// drop echoes the value it received, so one full-size object already fills
	// ~65 KB of JSON; the budget holds one of those plus smaller neighbours and
	// never two full ones. Measured on the real JSON (see takeDrops), not on an
	// estimate: escaping and the envelope are part of the cost.
	boardsDropEventBytes  = 70 * 1024
	boardsDropMaxObjects = 64

	// boardsStatsEvery is the cadence of the one write-stats line.
	boardsStatsEvery = 10 * time.Second
)

// boardsPacer paces writes as objects per second and learns the board's budget
// with AIMD. It is a slot clock, not a delay: reserve() says how long the caller
// has to wait for the next slot, so a busy writer waits for the remainder of the
// interval instead of sleeping a fixed amount per packet.
type boardsPacer struct {
	mu   sync.Mutex
	rate float64
	next time.Time
	// stalls counts refusals since the last acknowledged object: at the floor the
	// rate cannot fall any further, so the hold itself backs off.
	stalls int
	// now is time.Now, and a fake in tests.
	now func() time.Time
}

func (p *boardsPacer) clock() time.Time {
	if p.now != nil {
		return p.now()
	}
	return time.Now()
}

// currentRate reports the pace in objects per second.
func (p *boardsPacer) currentRate() float64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.rate <= 0 {
		return boardsRateStart
	}
	return p.rate
}

// reserve claims the next write slot and returns how long the caller has to
// wait before writing.
func (p *boardsPacer) reserve() time.Duration {
	now := p.clock()
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.rate <= 0 {
		p.rate = boardsRateStart
	}
	if p.next.Before(now) {
		p.next = now
	}
	at := p.next
	p.next = at.Add(p.intervalLocked())
	return at.Sub(now)
}

func (p *boardsPacer) intervalLocked() time.Duration {
	rate := p.rate
	if rate <= 0 {
		rate = boardsRateStart
	}
	d := time.Duration(float64(time.Second) / rate)
	if d < time.Millisecond {
		d = time.Millisecond
	}
	return d
}

// onSuccess credits one acknowledged object and returns the new pace.
func (p *boardsPacer) onSuccess() float64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.rate <= 0 {
		p.rate = boardsRateStart
	}
	p.stalls = 0
	if p.rate < boardsRateMax {
		p.rate = math.Min(boardsRateMax, p.rate+boardsRateGrow)
	}
	return p.rate
}

// onRateLimited halves the pace, holds the next slot back and returns the new
// pace. Halving is the "multiplicative decrease" of AIMD: the refused object is
// resent, so an over-long refusal is expensive.
func (p *boardsPacer) onRateLimited() float64 {
	now := p.clock()
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.rate <= 0 {
		p.rate = boardsRateStart
	}
	p.rate = math.Max(boardsRateMin, p.rate*boardsRateCut)
	p.stalls++
	shift := p.stalls - 1
	if shift > 2 {
		shift = 2 // 250 ms -> 500 ms -> 1 s, the ceiling
	}
	hold := boardsRateHold << uint(shift)
	if hold > boardsRateHoldMax {
		hold = boardsRateHoldMax
	}
	if until := now.Add(hold); p.next.Before(until) {
		p.next = until
	}
	return p.rate
}

// ---- внутренние типы ----

type boardsInfo struct {
	hash         string
	name         string
	userHash     string // payload.u из JWT
	jwt          string
	cookies      []*http.Cookie
	wsHost       string
	session      string // пустой до participant-connected/431
	dashboard    string
	currentSlide string
}

type boardsSession struct {
	Info    boardsInfo
	Conn    *websocket.Conn
	Queue   chan []byte
	writeMu sync.Mutex
	ack     atomic.Int64

	// participant — тот, что мы отправили в subscribe. Это userHash из JWT.
	participant atomic.Pointer[string]

	// creatorHash — серверный хэш (из participant-connected/431).
	creatorHash atomic.Pointer[string]

	// pending holds every write that is on the wire and not acknowledged yet, by
	// its socket.io ack id. A RATE_LIMITED answer names the event the board
	// refused and nothing else does, so this map is what turns a refusal into a
	// retry instead of a lost frame.
	pending      map[int64]boardsOutgoing
	pendingBytes int
	pendMu       sync.Mutex

	// retry holds refused writes in the order the board refused them. The writer
	// drains it before the fresh queue, so a refusal cannot reorder the stream.
	retry   []boardsOutgoing
	retryMu sync.Mutex
	retryCh chan struct{}

	// dropList coalesces the objects the peer sent into one drop-objects event
	// per window; each of those events costs the same budget as a payload write.
	dropList []map[string]interface{}
	dropMu   sync.Mutex
	dropCh   chan struct{}

	// stop is closed when this connection's serve loop ends: the writer, dropper
	// and stats goroutines belong to one socket and must not outlive it.
	stop chan struct{}
}

// boardsOutgoing is one unit of work for the writer: either a payload object
// (modify-objects) or the deletion of objects the peer sent (drop-objects). Both
// spend the board's per-second event budget, so both are paced and both are
// retried when the board refuses them. Protocol frames (ping/pong, subscribe,
// heartbeat) never go through here: a held pong closes the socket.
type boardsOutgoing struct {
	pkt []byte
	// drop is the prebuilt drop-objects event; pkt is empty for a drop.
	drop map[string]interface{}
	// tries counts the writes already made for this unit; it bounds a retry
	// that keeps being refused.
	tries int
	// at is when the write left, so a silent ack can be timed out.
	at time.Time
}

// pendingSize is what one in-flight write costs against boardsMaxPendingBytes.
func pendingSize(w boardsOutgoing) int { return len(w.pkt) + dropEventBytes(w.drop) }

// trackPending records a write in flight and returns any writes evicted to stay
// within the count and byte caps. The payload of one write is up to 48 KiB, so
// an unacknowledged set has to be bounded by bytes, not only by count: this code
// also runs inside the phone.
func (s *boardsSession) trackPending(id int64, out boardsOutgoing) []boardsOutgoing {
	size := pendingSize(out)
	s.pendMu.Lock()
	defer s.pendMu.Unlock()
	var evicted []boardsOutgoing
	for len(s.pending) >= boardsMaxPending ||
		(len(s.pending) > 0 && s.pendingBytes+size > boardsMaxPendingBytes) {
		oldID, oldest, ok := s.oldestPendingLocked()
		if !ok {
			break
		}
		delete(s.pending, oldID)
		s.pendingBytes -= pendingSize(oldest)
		evicted = append(evicted, oldest)
	}
	s.pending[id] = out
	s.pendingBytes += size
	return evicted
}

func (s *boardsSession) oldestPendingLocked() (int64, boardsOutgoing, bool) {
	var (
		bestID   int64
		best     boardsOutgoing
		bestSeen bool
	)
	for id, w := range s.pending {
		if !bestSeen || w.at.Before(best.at) {
			bestID, best, bestSeen = id, w, true
		}
	}
	return bestID, best, bestSeen
}

// takePending removes and returns the write an ack answers.
func (s *boardsSession) takePending(id int64) (boardsOutgoing, bool) {
	s.pendMu.Lock()
	out, ok := s.pending[id]
	if ok {
		delete(s.pending, id)
		s.pendingBytes -= pendingSize(out)
	}
	s.pendMu.Unlock()
	return out, ok
}

func (s *boardsSession) forgetPending(id int64) {
	s.pendMu.Lock()
	if out, ok := s.pending[id]; ok {
		delete(s.pending, id)
		s.pendingBytes -= pendingSize(out)
	}
	s.pendMu.Unlock()
}

func (s *boardsSession) pendingCount() int {
	s.pendMu.Lock()
	defer s.pendMu.Unlock()
	return len(s.pending)
}

func (s *boardsSession) pendingByteCount() int {
	s.pendMu.Lock()
	defer s.pendMu.Unlock()
	return s.pendingBytes
}

// expiredPending forgets the writes the board never answered: the event was
// dropped silently or its ack was lost, and neither may hold memory forever.
func (s *boardsSession) expiredPending(now time.Time, age time.Duration) []boardsOutgoing {
	s.pendMu.Lock()
	defer s.pendMu.Unlock()
	var out []boardsOutgoing
	for id, w := range s.pending {
		if now.Sub(w.at) <= age {
			continue
		}
		out = append(out, w)
		delete(s.pending, id)
		s.pendingBytes -= pendingSize(w)
	}
	return out
}

// retryObject puts a refused write back in the writer's queue.
func (s *boardsSession) retryObject(out boardsOutgoing) {
	out.tries++
	s.retryMu.Lock()
	s.retry = append(s.retry, out)
	s.retryMu.Unlock()
	select {
	case s.retryCh <- struct{}{}:
	default:
	}
}

func (s *boardsSession) takeRetry() (boardsOutgoing, bool) {
	s.retryMu.Lock()
	defer s.retryMu.Unlock()
	if len(s.retry) == 0 {
		return boardsOutgoing{}, false
	}
	out := s.retry[0]
	s.retry = s.retry[1:]
	if len(s.retry) == 0 {
		s.retry = nil
	}
	return out, true
}

// queueDrops hands the peer's objects to the coalescer: one drop-objects per
// window answers many server-modify-objects events.
func (s *boardsSession) queueDrops(objects []map[string]interface{}) {
	if len(objects) == 0 {
		return
	}
	s.dropMu.Lock()
	s.dropList = append(s.dropList, objects...)
	s.dropMu.Unlock()
	select {
	case s.dropCh <- struct{}{}:
	default:
	}
}

// takeDrops builds one drop-objects event from the queued objects: as many as
// stay under boardsDropEventBytes once the event is marshalled, oldest first.
// The board's measured ceiling is 48 KiB of decoded value per object, and a drop
// echoes the value it received, so the budget is sized to hold one full-size
// object plus its smaller neighbours - not two full ones. The size is checked on
// the real JSON, not estimated: escaping and the envelope are part of the cost.
func (s *boardsSession) takeDrops(participant string) (map[string]interface{}, bool) {
	s.dropMu.Lock()
	defer s.dropMu.Unlock()
	var event map[string]interface{}
	n := 0
	for n < len(s.dropList) && n < boardsDropMaxObjects {
		cand := buildDropObjects(s.dropList[:n+1], participant)
		body, err := json.Marshal(cand)
		if err != nil || (n > 0 && len(body) > boardsDropEventBytes) {
			break
		}
		event = cand
		n++
	}
	if n == 0 {
		return nil, false
	}
	s.dropList = s.dropList[n:]
	if len(s.dropList) == 0 {
		s.dropList = nil
	}
	return event, true
}

func (s *boardsSession) dropsPending() int {
	s.dropMu.Lock()
	defer s.dropMu.Unlock()
	return len(s.dropList)
}

// dropEventBytes is what a prebuilt drop event costs against the pending byte
// cap; a payload write costs len(pkt).
func dropEventBytes(event map[string]interface{}) int {
	if event == nil {
		return 0
	}
	body, err := json.Marshal(event)
	if err != nil {
		return 0
	}
	return len(body)
}

func (s *boardsSession) safeWrite(msgType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.Conn.WriteMessage(msgType, data)
}

func shortStr(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// nextAck reserves the socket.io ack id of one event. Every event we send
// carries one and the board answers each with "43<id>[...]", which is the only
// place a refusal shows up - so the id is what ties a refused object back to the
// payload that has to be written again.
func (s *boardsSession) nextAck() int64 { return s.ack.Add(1) - 1 }

// writeEventWithAck writes one dashboard event under a pre-reserved ack id: the
// caller records the object as in flight before this call, or a fast refusal can
// arrive for an id nobody knows.
func (s *boardsSession) writeEventWithAck(ack int64, ns string, obj interface{}) error {
	payload, err := json.Marshal([]interface{}{ns, obj})
	if err != nil {
		return err
	}
	msg := fmt.Sprintf("42%d%s", ack, payload)
	return s.safeWrite(websocket.TextMessage, []byte(msg))
}

func (s *boardsSession) writeEventObj(ns string, obj interface{}) error {
	return s.writeEventWithAck(s.nextAck(), ns, obj)
}

func (s *boardsSession) writeRaw(raw string) error {
	return s.safeWrite(websocket.TextMessage, []byte(raw))
}

// ---- транспорт ----

type BoardsTransport struct {
	*transport.BaseTransport

	url string

	session atomic.Pointer[boardsSession]

	onDataMu sync.RWMutex
	onData   func([]byte)

	closeOnce sync.Once
	done      chan struct{}

	cookieJar *cookiejar.Jar
	jarMu     sync.RWMutex

	errNotifier func(err error, transportName, url, html, reason string)

	// goodSocketURL is the engine.io endpoint that last completed the handshake:
	// a shard host and the site host behave differently per deployment, and this
	// keeps a reconnect on the endpoint that actually served us.
	goodSocketURL atomic.Value

	// warnNetworkTransport keeps the "the board refuses visible IP traffic"
	// advice to one line: every refused write repeats the same ack.
	warnNetworkTransport atomic.Bool

	// pacer is the write budget learned for this board. It lives on the
	// transport, not on the session, so a reconnect keeps what was learned
	// instead of climbing back to the ceiling from scratch.
	pacer boardsPacer

	// rateLog keeps the rate lines to one per change. One line per ack is what
	// this replaces: dozens of RATE_LIMITED acks a second used to print a line
	// each and bury everything else.
	rateLogMu   sync.Mutex
	rateLogged  int
	rateCeiling bool

	// Write counters for the periodic stats line. Refusals stay split on
	// purpose: PAYLOAD_TOO_LARGE is our own frame-size bug, NETWORK_TRANSPORT is
	// a key mismatch between the peers, and one merged counter would hide which
	// of the two is happening.
	statSent        atomic.Int64 // payload writes the board acknowledged
	statDropEvents  atomic.Int64 // drop-objects events sent
	statRetried     atomic.Int64 // refused writes put back in the queue
	statRefused     atomic.Int64 // RATE_LIMITED acks
	statTooLarge    atomic.Int64 // PAYLOAD_TOO_LARGE acks
	statKeyMismatch atomic.Int64 // NETWORK_TRANSPORT acks
	statDroppedDead atomic.Int64 // writes given up on (retry cap or silent ack)
	statOverload    atomic.Int64 // in-flight writes evicted by the memory cap
}

func NewBoardsTransport(rawURL string, config transport.TransportConfig) *BoardsTransport {
	jar, _ := cookiejar.New(nil)
	return &BoardsTransport{
		BaseTransport: transport.NewBaseTransport(config),
		url:           rawURL,
		done:          make(chan struct{}),
		cookieJar:     jar,
	}
}

// SetErrorNotifier installs a callback for out-of-band errors. Called once
// by the manager. Boards currently never returns sentinel errors from
// fetchDocInfo (its captcha path is the old showcaptchafast, which the
// internal PoW solver handles), but the hook is wired for parity with the
// other transports.
func (t *BoardsTransport) SetErrorNotifier(fn func(err error, transportName, url, html, reason string)) {
	t.errNotifier = fn
}

// PreferredBatchBytes tells the coalescing layer how many bytes fit into one
// modify-objects event. The board's own limit is boardsMaxObjectBytes; the
// batch stays under it so a segment-heavy burst cannot tip one message over
// the cap and lose the whole batch to PAYLOAD_TOO_LARGE.
func (t *BoardsTransport) PreferredBatchBytes() int { return boardsBatchBytes }

// PreferredBatchLingerMs widens the coalescing window: one board message costs
// a round trip (measured 0.07-0.17 s on the board's own shard) and, more
// importantly, a slot in the board's per-second budget. At 20 ms a browsing
// tunnel wrote ~50 objects a second, each one a chance to be answered
// RATE_LIMITED; 100 ms turns the same bytes into fewer, fuller objects. The byte
// ceiling (boardsBatchBytes) is unchanged: it is the board's own limit.
func (t *BoardsTransport) PreferredBatchLingerMs() int { return 100 }

func (t *BoardsTransport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}
	hash := extractBoardsHash(t.url)
	if hash == "" {
		return fmt.Errorf("boards: no hash in URL %q", t.url)
	}

	name := randomGuestName()
	info, err := t.authorize(hash, name)
	if err != nil {
		return fmt.Errorf("boards auth: %w", err)
	}
	utils.Debugf("[BOARDS] auth OK: hash=%s name=%q userHash=%s dashboard=%q wsHost=%s",
		info.hash, info.name, info.userHash, info.dashboard, info.wsHost)

	t.closeOnce = sync.Once{}
	t.done = make(chan struct{})
	utils.SafeGo("boards.connect", func() { t.connectLoop(info) })

	return nil
}

func (t *BoardsTransport) Stop() error {
	t.closeOnce.Do(func() { close(t.done) })
	if s := t.session.Load(); s != nil && s.Conn != nil {
		s.Conn.Close()
	}
	t.SetConnected(false)
	return t.BaseTransport.Stop()
}

func (t *BoardsTransport) Send(data []byte) error {
	if len(data) == 0 {
		return nil
	}
	s := t.session.Load()
	if s == nil {
		return fmt.Errorf("boards: no session")
	}
	cp := make([]byte, len(data))
	copy(cp, data)
	select {
	case s.Queue <- cp:
		return nil
	case <-t.done:
		return fmt.Errorf("boards: closed")
	default:
		return fmt.Errorf("boards: queue full")
	}
}

func (t *BoardsTransport) Receive(cb func([]byte)) {
	t.onDataMu.Lock()
	t.onData = cb
	t.onDataMu.Unlock()
}

func (t *BoardsTransport) IsConnected() bool {
	return t.BaseTransport.IsConnected()
}

// ---- авторизация ----
//
// 1) GET /whiteboard/?hash=<hash>   → начальные cookies
//    (может редиректнуть на showcaptchafast — тогда проходим капчу)
// 2) POST /api request-guest-token  → Set-Cookie token_<hash>=<JWT>
// 3) POST /api get-whiteboard-info  → dashboard/current_slide/ws_host

// errCaptchaRequired — sentinel error: сервер требует капчу.
var errCaptchaRequired = fmt.Errorf("captcha required")

// getAllowCaptcha делает GET и, если встречает редирект на showcaptchafast,
// возвращает errCaptchaRequired.
func (t *BoardsTransport) getAllowCaptcha(client *http.Client, u, hash string) error {
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", boardsUA)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("Referer", "https://"+boardsBase+"/guest/?hash="+hash)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		loc := resp.Header.Get("Location")
		if strings.Contains(loc, "showcaptchafast") {
			utils.Debugf("[BOARDS] redirect to captcha: %s", shortStr(loc, 100))
			return errCaptchaRequired
		}
	}
	io.Copy(io.Discard, resp.Body)
	return nil
}

func (t *BoardsTransport) authorize(hash, name string) (boardsInfo, error) {
	jar := t.jar()
	client := &http.Client{
		Jar:       jar,
		Transport: carrierTransport(),
		Timeout:   15 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	docURL := "https://" + boardsBase + "/whiteboard/?hash=" + hash

	// GET whiteboard — может вернуть 302 на showcaptchafast
	if err := t.getAllowCaptcha(client, docURL, hash); err != nil {
		if err == errCaptchaRequired {
			utils.Debugf("[BOARDS] captcha required, solving...")
			if _, cerr := solveCaptcha(docURL, jar, boardsUA); cerr != nil {
				return boardsInfo{}, fmt.Errorf("captcha solve: %w", cerr)
			}
			utils.Debugf("[BOARDS] captcha solved, re-fetching whiteboard")

			// После капчи повторяем GET /whiteboard — сервер выдаёт
			// свежие cookies, нужные для последующих /api запросов.
			if err := t.getAllowCaptcha(client, docURL, hash); err != nil && err != errCaptchaRequired {
				return boardsInfo{}, fmt.Errorf("GET whiteboard (post-captcha): %w", err)
			}
		} else {
			return boardsInfo{}, err
		}
	}

	state, err := t.getWhiteboardInfo(client, hash)
	if err != nil {
		// The info call doubles as the guest admission check. A signed-in browser
		// gets the board back; an anonymous one is answered with code 204 and a
		// redirect to /guest, which is the guest landing page and not a refusal —
		// the session is minted by request-guest-token right after, the same call
		// that page makes (measured 2026-11-08 on a board with link access open:
		// info -> 204 + /guest, then request-guest-token -> Set-Cookie
		// token_<hash>). Only real refusals go up: 212 "Guest mode disabled"
		// (link access off) and the hash errors.
		var apiErr *boardsAPIError
		if !errors.As(err, &apiErr) || !boardsGuestLanding(apiErr) {
			return boardsInfo{}, err
		}
		utils.Debugf("[BOARDS] /api answered %d %q (guest landing), continuing to request-guest-token", apiErr.Code, apiErr.Description)
		state = nil
	}

	guestContent := map[string]string{"name": name, "hash": hash}
	if csrf := state["csrf"]; csrf != "" {
		// wbpost() sends the csrf it got from any earlier /api reply.
		guestContent["csrf"] = csrf
	}

	guest, err := t.postAPI(client, hash, "request-guest-token", guestContent)
	if err != nil {
		return boardsInfo{}, fmt.Errorf("request-guest-token: %w", err)
	}

	u, _ := url.Parse("https://" + boardsBase)
	var jwt string
	for _, c := range jar.Cookies(u) {
		if c.Name == "token_"+hash {
			jwt = c.Value
		}
	}
	if jwt == "" {
		// An open board answers with a redirect back to the board and the
		// token cookie; a closed one redirects to the site root without a word.
		if guest.Redirect != "" && !strings.Contains(guest.Redirect, hash) {
			return boardsInfo{}, fmt.Errorf(
				"token_%s not found: request-guest-token redirected to %s and set no cookie; the board is probably closed to guests",
				hash, guest.Redirect)
		}
		return boardsInfo{}, fmt.Errorf("token_%s not found: request-guest-token set no cookie", hash)
	}

	payload := jwtPayload(jwt)
	userHash, _ := payload["u"].(string)

	// The token turns the anonymous info into the board's own: the first call
	// answered 204 + /guest (guest landing), so the dashboard and the shard are
	// only known now. The guest page does the same — it follows the redirect to
	// /whiteboard/?hash=... and the app asks again. Without this the dashboard
	// stays empty, subscribe-slide-dashboard joins no room, and peer packets
	// never reach each other (measured 2026-11-08: both peers logged
	// "subscribed", zero server-modify-objects in either direction).
	if state == nil {
		state = map[string]string{}
	}
	if tokenState, terr := t.getWhiteboardInfo(client, hash); terr != nil {
		utils.Debugf("[BOARDS] board info with token failed: %v", terr)
	} else {
		mergeBoardState(state, tokenState)
	}
	if state["dashboard"] == "" {
		utils.Debugf("[BOARDS] board info names no dashboard; subscribe will carry the presentation only")
	}

	var cookies []*http.Cookie
	cookies = append(cookies, jar.Cookies(u)...)

	wsHost := state["ws_host"]
	if wsHost == "" {
		wsHost = boardsSocketHostDefault
	}

	return boardsInfo{
		hash:         hash,
		name:         name,
		userHash:     userHash,
		jwt:          jwt,
		cookies:      cookies,
		wsHost:       wsHost,
		session:      "",
		dashboard:    state["dashboard"],
		currentSlide: state["current_slide"],
	}, nil
}

// apiRequest builds a POST /api call: the action and its content (JSON,
// base64-encoded) in a JSON body. The API used to take a form and now
// answers 415 "Request body must use a JSON Content-Type" to one.
func apiRequest(hash, action string, content interface{}) *http.Request {
	raw, _ := json.Marshal(content)
	body, _ := json.Marshal(map[string]string{
		"action":  action,
		"content": base64.StdEncoding.EncodeToString(raw),
	})
	req, _ := http.NewRequest("POST", "https://"+boardsBase+"/api", bytes.NewReader(body))
	req.Header.Set("User-Agent", boardsUA)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	req.Header.Set("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Set("Referer", "https://"+boardsBase+"/guest/?hash="+hash)
	req.Header.Set("Origin", "https://"+boardsBase)
	return req
}

// apiResponse is the part of an /api reply we act on. The server answers
// HTTP 200 even when it refuses the call and names the reason in "error", so
// a status-code check alone reads a refusal as a success.
type apiResponse struct {
	Error *struct {
		Code        int    `json:"code"`
		Description string `json:"description"`
	} `json:"error"`
	Redirect string `json:"redirect"`
	CSRF     string `json:"csrf"`
}

// boardsAPIError is a refusal carried in the JSON body of a 200 response.
type boardsAPIError struct {
	Action      string
	Code        int
	Description string
	Redirect    string
}

func (e *boardsAPIError) Error() string {
	msg := fmt.Sprintf("/api %s: code %d %q", e.Action, e.Code, e.Description)
	if e.Redirect != "" {
		msg += ", redirect " + e.Redirect
	}
	switch e.Code {
	case 212:
		msg += "; the board is closed to guests — ask the owner to enable link sharing (Доступ по ссылке) and to re-send the /whiteboard/?hash=... link"
	case 250:
		msg += "; the hash names no board"
	case 200:
		msg += "; the hash is not a 32-character board hash"
	}
	return msg
}

// decodeAPIResponse turns a 200 body into its refusal, if it carries one.
func decodeAPIResponse(action string, body []byte) (apiResponse, error) {
	var r apiResponse
	if err := json.Unmarshal(body, &r); err != nil {
		return r, nil // not a refusal; leave the body to the caller
	}
	if r.Error == nil {
		return r, nil
	}
	return r, &boardsAPIError{
		Action:      action,
		Code:        r.Error.Code,
		Description: r.Error.Description,
		Redirect:    r.Redirect,
	}
}

func (t *BoardsTransport) postAPI(client *http.Client, hash, action string, content interface{}) (apiResponse, error) {
	resp, err := client.Do(apiRequest(hash, action, content))
	if err != nil {
		return apiResponse{}, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		n := len(body)
		if n > 200 {
			n = 200
		}
		return apiResponse{}, fmt.Errorf("status %d: %s", resp.StatusCode, string(body[:n]))
	}
	return decodeAPIResponse(action, body)
}

func (t *BoardsTransport) getWhiteboardInfo(client *http.Client, hash string) (map[string]string, error) {
	resp, err := client.Do(apiRequest(hash, "get-whiteboard-info", map[string]string{"hash": hash}))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	// A 200 with "error" is a refusal, not an empty board: the workspace line
	// in state["ws_host"] would otherwise just fall back to the default host.
	if _, apiErr := decodeAPIResponse("get-whiteboard-info", body); apiErr != nil {
		return nil, apiErr
	}

	var info struct {
		CSRF         string `json:"csrf"`
		Presentation struct {
			Properties struct {
				CurrentSlide string `json:"current_slide"`
			} `json:"properties"`
			Items string `json:"items"`
		} `json:"presentation"`
		SocketServers []struct {
			IP string `json:"ip"`
		} `json:"socket_servers"`
	}
	if err := json.Unmarshal(body, &info); err != nil {
		return nil, err
	}
	out := map[string]string{}
	if info.CSRF != "" {
		out["csrf"] = info.CSRF
	}
	if info.Presentation.Properties.CurrentSlide != "" {
		out["current_slide"] = info.Presentation.Properties.CurrentSlide
		out["dashboard"] = info.Presentation.Properties.CurrentSlide
	}
	if info.Presentation.Items != "" {
		if rawItems, err := base64.StdEncoding.DecodeString(info.Presentation.Items); err == nil {
			var arr []map[string]interface{}
			if json.Unmarshal(rawItems, &arr) == nil && len(arr) > 0 {
				if h, _ := arr[0]["hash"].(string); h != "" && out["dashboard"] == "" {
					out["dashboard"] = h
					out["current_slide"] = h
				}
			}
		}
	}
	if len(info.SocketServers) > 0 {
		out["ws_host"] = info.SocketServers[0].IP
	}
	return out, nil
}

func jwtPayload(jwt string) map[string]interface{} {
	parts := strings.Split(jwt, ".")
	if len(parts) < 2 {
		return nil
	}
	pad := (4 - len(parts[1])%4) % 4
	b := parts[1] + strings.Repeat("=", pad)
	raw, err := base64.URLEncoding.DecodeString(b)
	if err != nil {
		return nil
	}
	var m map[string]interface{}
	_ = json.Unmarshal(raw, &m)
	return m
}

func extractBoardsHash(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	return u.Query().Get("hash")
}

func randomGuestName() string {
	var b [3]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("guest_%06x", time.Now().UnixNano()&0xffffff)
	}
	return "guest_" + hex.EncodeToString(b[:])
}

func randomHex(n int) string {
	buf := make([]byte, n/2)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%0*x", n, mrand.Int63())
	}
	return hex.EncodeToString(buf)
}

// ---- WS lifecycle ----

func (t *BoardsTransport) connectLoop(info boardsInfo) {
	attempt := 0
	for {
		select {
		case <-t.done:
			return
		default:
		}
		began := time.Now()
		err := t.connectAndServe(info)
		lasted := time.Since(began)
		if err != nil {
			utils.Debugf("[BOARDS] ws error after %v: %v", lasted.Round(time.Second), err)
			if utils.Throttled("boards.drop", time.Minute) {
				utils.Infof("[BOARDS] connection to the board dropped after %v: %v; reconnecting", lasted.Round(time.Second), err)
			}
		}
		t.SetConnected(false)
		if lasted > time.Minute {
			// A session that held is not part of a failure streak.
			attempt = 0
		}
		select {
		case <-t.done:
			return
		case <-time.After(reconnectBackoffBoards(attempt)):
		}
		attempt++
		if attempt > 10 {
			attempt = 10
		}
	}
}

func reconnectBackoffBoards(n int) time.Duration {
	if n < 1 {
		n = 1
	}
	shift := n - 1
	if shift > 4 {
		shift = 4
	}
	d := 500 * time.Millisecond * time.Duration(1<<uint(shift))
	if d > 15*time.Second {
		d = 15 * time.Second
	}
	d += time.Duration(mrand.Int63n(int64(d/2) + 1))
	return d
}

// mergeBoardState fills dst with the non-empty entries of src. The info reply
// fetched with the guest token supersedes the anonymous one, but a field the
// second reply omits must not erase what the first one named.
func mergeBoardState(dst, src map[string]string) {
	for k, v := range src {
		if v != "" {
			dst[k] = v
		}
	}
}

// boardsAck is what an event ack says: which event it answers, and whether the
// board refused that event.
type boardsAck struct {
	ID       int64
	Known    bool // a "43<id>[...]" packet: an ack we can match to a write
	Error    bool
	Code     int
	Security string
}

// parseBoardsAck reads a socket.io ack packet. "43" with no id is not an ack -
// the board also answers with a bare "43[...]" - and an id whose body does not
// parse leaves Error false, i.e. an accepted event.
func parseBoardsAck(raw []byte) boardsAck {
	var a boardsAck
	if !bytes.HasPrefix(raw, []byte("43")) {
		return a
	}
	i := 2
	for ; i < len(raw) && raw[i] >= '0' && raw[i] <= '9'; i++ {
		if i-2 > 17 {
			return boardsAck{} // not an id we could have assigned
		}
		a.ID = a.ID*10 + int64(raw[i]-'0')
	}
	if i == 2 {
		return boardsAck{}
	}
	a.Known = true
	idx := bytes.IndexByte(raw[i:], '[')
	if idx < 0 {
		return a
	}
	var arr []struct {
		Error *struct {
			Code         int    `json:"code"`
			SecurityCode string `json:"securityCode"`
		} `json:"error"`
	}
	if err := json.Unmarshal(raw[i+idx:], &arr); err != nil || len(arr) == 0 {
		return a
	}
	if e := arr[0].Error; e != nil {
		a.Error = true
		a.Code = e.Code
		a.Security = e.SecurityCode
	}
	return a
}

// ackSecurityCode returns the securityCode of an event ack that carries an
// error, or "" for a plain ack. The board answers e.g.
// `433[{"error":{"code":200,"securityCode":"NETWORK_TRANSPORT"}}]`.
func ackSecurityCode(raw []byte) string {
	return parseBoardsAck(raw).Security
}

// handleAck reads the board's answer to one of our events. Every write goes out
// with an ack id, so a refusal only ever arrives here: before this it was one
// debug line and the payload it named was thrown away.
func (t *BoardsTransport) handleAck(sess *boardsSession, raw []byte) {
	ack := parseBoardsAck(raw)
	if !ack.Known {
		return
	}
	out, tracked := sess.takePending(ack.ID)
	if !tracked {
		// Heartbeats, subscribe and the keepalive share the id space; only paced
		// writes are tracked, and an unknown code is still worth one line.
		if ack.Error && ack.Security != "" && ack.Security != boardsRateLimited &&
			utils.Throttled("boards.ack.untracked", 30*time.Second) {
			utils.Debugf("[BOARDS] event ack error: %s", shortStr(string(raw), 240))
		}
		return
	}

	if !ack.Error {
		if out.drop != nil {
			t.statDropEvents.Add(1)
		} else {
			t.statSent.Add(1)
		}
		t.noteRate(t.pacer.onSuccess(), "")
		return
	}

	if ack.Security == boardsRateLimited {
		// The board names the object it refused: the write goes back to the
		// front of the writer and the pace halves, so the next second asks for
		// less than the board just refused.
		t.statRefused.Add(1)
		t.noteRate(t.pacer.onRateLimited(), "rate limited")
		if out.tries >= boardsMaxTries {
			t.deadWrite(out, ack.Security)
			return
		}
		t.statRetried.Add(1)
		sess.retryObject(out)
		return
	}

	// NETWORK_TRANSPORT, PAYLOAD_TOO_LARGE and the rest name the bytes, not the
	// timing: the same object meets the same answer, so a retry only burns the
	// budget.
	if ack.Security == boardsNetworkTransport && t.warnNetworkTransport.CompareAndSwap(false, true) {
		// The board decodes the object value and refuses anything that carries
		// visible IPv4/TCP traffic (measured 2026-11-08: a real SYN inside the
		// batch is refused, the same frame with a zero-filled packet passes).
		// Sealed frames look random, so this is what a peer without
		// -encryption-key hits before anything reaches the other side.
		utils.Infof("[BOARDS] the board refuses frames carrying visible IP traffic; both peers must run with the same -encryption-key")
	}
	t.deadWrite(out, ack.Security)
}

// deadWrite accounts a write that will not be retried. The refusal counters stay
// split on purpose: PAYLOAD_TOO_LARGE means the frame we built was too big and
// NETWORK_TRANSPORT means the peers' keys disagree - one merged counter would
// make the two indistinguishable in the log a day later.
func (t *BoardsTransport) deadWrite(out boardsOutgoing, code string) {
	switch code {
	case "PAYLOAD_TOO_LARGE":
		t.statTooLarge.Add(1)
	case boardsNetworkTransport:
		t.statKeyMismatch.Add(1)
	}
	t.statDroppedDead.Add(1)
	if out.drop != nil {
		// An undeleted object is clutter on the board; no user payload is lost.
		return
	}
	if utils.Throttled("boards.deadwrite", 30*time.Second) {
		utils.Infof("[BOARDS] write gave up after %d tries (%q), counted as dropped_dead", out.tries, code)
	}
}

func (t *BoardsTransport) connectAndServe(info boardsInfo) error {

	header := http.Header{}
	header.Set("User-Agent", boardsUA)
	header.Set("Origin", "https://"+boardsBase)
	header.Set("Accept-Language", "en-US,en;q=0.9")

	var cookieParts []string
	for _, c := range info.cookies {
		cookieParts = append(cookieParts, c.Name+"="+c.Value)
	}
	if !strings.Contains(strings.Join(cookieParts, ";"), "token_"+info.hash) {
		cookieParts = append(cookieParts, "token_"+info.hash+"="+info.jwt)
	}
	header.Set("Cookie", strings.Join(cookieParts, "; "))

	// The board API hands out a shard host (ws_host, e.g. socket33.boards.yandex.ru),
	// but measured 2026-11-08 that shard answers 404 for /socket.io/ while the site
	// host answers the engine.io handshake (200 with sid/upgrades) once the Origin
	// header is present. Try the endpoint that worked last time, then the shard,
	// then the site host.
	var conn *websocket.Conn
	var dialErr error
	dialed := ""
	for _, candidate := range t.socketURLs(info) {
		dialer := websocket.Dialer{
			HandshakeTimeout: 15 * time.Second,
			NetDialContext:   dialIPv4First,
		}
		utils.ConfigureWebsocketDialerProxy(&dialer, candidate)
		utils.Debugf("[BOARDS] dial %s", candidate)
		c, resp, err := dialer.Dial(candidate, header)
		if err == nil {
			conn = c
			dialed = candidate
			t.goodSocketURL.Store(candidate)
			break
		}
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		dialErr = fmt.Errorf("dial %s (http %d): %w", candidate, status, err)
	}
	if conn == nil {
		if dialErr == nil {
			dialErr = fmt.Errorf("dial: no socket endpoint configured")
		}
		return dialErr
	}
	utils.Debugf("[BOARDS] WS connected: %s (shard %q)", dialed, info.wsHost)
	if info.wsHost != "" && !strings.Contains(dialed, info.wsHost) {
		// The room is per shard, so a site-host dial only works if the load
		// balancer happens to put both peers on one front pod. Say so: the
		// symptom otherwise is a silently dead tunnel.
		utils.Debugf("[BOARDS] dialed %s instead of the board's shard %s: peers may land on different pods and never share a room", dialed, info.wsHost)
	}

	participant := info.userHash
	creator := info.userHash
	sess := &boardsSession{
		Info:    info,
		Conn:    conn,
		Queue:   make(chan []byte, t.GetConfig().MaxQueueSize),
		pending: make(map[int64]boardsOutgoing),
		retryCh: make(chan struct{}, 1),
		dropCh:  make(chan struct{}, 1),
		stop:    make(chan struct{}),
	}
	sess.participant.Store(&participant)
	sess.creatorHash.Store(&creator)
	t.session.Store(sess)

	if err := t.handshake(sess); err != nil {
		conn.Close()
		t.session.Store(nil)
		close(sess.stop)
		return fmt.Errorf("handshake: %w", err)
	}

	_ = conn.SetReadDeadline(time.Time{})

	// The writer, the drop coalescer and the stats line belong to this socket:
	// when it dies they stop, or the next session would share the budget with a
	// writer whose connection is already gone.
	defer close(sess.stop)

	t.SetConnected(true)
	utils.Debugf("[BOARDS] write pace %.1f objects/s", t.pacer.currentRate())
	utils.SafeGo("boards.writer", func() { t.writerLoop(sess) })
	utils.SafeGo("boards.dropper", func() { t.dropLoop(sess) })
	utils.SafeGo("boards.stats", func() { t.statsLoop(sess) })

	// No client-side engine.io ping: in EIO=4 the server pings ("2") and the
	// client answers ("3", see handleMessage). A client "2" is an invalid
	// heartbeat direction to an engine.io v4 server, which then closes the
	// socket, so the old pingLoop dropped the board every
	// boardsPingInterval. The dashboard heartbeat below keeps it busy.
	kaStop := make(chan struct{})
	utils.SafeGo("boards.keepalive", func() { t.keepAliveLoop(sess, kaStop) })
	defer close(kaStop)

	for {
		select {
		case <-t.done:
			return nil
		default:
		}

		_ = conn.SetReadDeadline(time.Now().Add(boardsReadDeadline))
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		_ = conn.SetReadDeadline(time.Now().Add(boardsReadDeadline))

		t.handleMessage(sess, msg)
	}
}

func (t *BoardsTransport) handshake(sess *boardsSession) error {
	conn := sess.Conn
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))

	if _, err := readRaw(conn); err != nil {
		return fmt.Errorf("engine.io hello: %w", err)
	}

	if err := sess.writeRaw("40"); err != nil {
		return err
	}
	if _, err := readRaw(conn); err != nil {
		return fmt.Errorf("socket.io connect ack: %w", err)
	}

	if err := sess.writeEventObj("im", map[string]interface{}{
		"operation": "subscribe", "user": nil,
	}); err != nil {
		return err
	}

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		m, err := readRaw(conn)
		if err != nil {
			return fmt.Errorf("wait subscribed: %w", err)
		}
		if bytes.Contains(m, []byte(`"subscribed"`)) {
			break
		}
	}

	part := *sess.participant.Load()
	utils.Debugf("[BOARDS] subscribe-slide-dashboard participant=%s", shortStr(part, 8))
	if err := t.sendSubscribe(sess, part); err != nil {
		return err
	}

	_ = conn.SetReadDeadline(time.Now().Add(boardsHandshakeWait))
	deadline = time.Now().Add(boardsHandshakeWait)
	for time.Now().Before(deadline) {
		m, err := readRaw(conn)
		if err != nil {
			return fmt.Errorf("wait 431: %w", err)
		}
		t.handleMessage(sess, m)
		if bytes.HasPrefix(m, []byte("431[")) {
			break
		}
		if bytes.Contains(m, []byte(`"subscribed":true`)) &&
			bytes.Contains(m, []byte(`"dashboard_link"`)) {
			break
		}
	}

	_ = conn.SetReadDeadline(time.Time{})
	utils.Debugf("[BOARDS] handshake done")
	return nil
}

func readRaw(conn *websocket.Conn) ([]byte, error) {
	_, msg, err := conn.ReadMessage()
	if err != nil {
		return nil, err
	}
	return msg, nil
}

func (t *BoardsTransport) sendSubscribe(sess *boardsSession, participant string) error {
	data := map[string]interface{}{
		"session":      sess.Info.session,
		"dashboard":    sess.Info.dashboard,
		"presentation": sess.Info.hash,
		"properties": map[string]interface{}{
			"guest_mode":                     true,
			"guest_role":                     1,
			"guest_password":                 nil,
			"guest_password_expiration_date": nil,
			"current_slide":                  sess.Info.currentSlide,
		},
		"participant_team_role": -1,
		"participant":           participant,
		"options": map[string]interface{}{
			"type": "landing",
			"participant": map[string]interface{}{
				"hash":         participant,
				"partner":      "yandex",
				"userHash":     sess.Info.userHash,
				"name":         sess.Info.name,
				"additional":   map[string]interface{}{"guest": true},
				"module":       "yandex",
				"presentation": sess.Info.hash,
				"identityCandidates": map[string]interface{}{
					"uidHash":    nil,
					"legacyHash": participant,
					"uid":        nil,
					"partner":    "yandex",
				},
				"module_type":            "yandex",
				"participantCaptionName": sess.Info.name,
			},
			"intermediate": "",
			"device": map[string]interface{}{
				"screen":              "674 x 619",
				"screen_width":        674,
				"screen_height":       619,
				"browser":             "Chrome",
				"browserVersion":      "153.0.0.0",
				"browserMajorVersion": 153,
				"mobile":              true,
				"os":                  "Android",
				"osVersion":           "15",
				"osMajorVersion":      15,
				"cookies":             true,
				"flashVersion":        "no check",
				"agent":               "Chrome",
				"appVersion":          boardsUA,
				"userAgent":           boardsUA,
				"appName":             "Netscape",
				"platform":            "MacIntel",
			},
		},
	}
	return sess.writeEventObj("dashboard", map[string]interface{}{
		"action":      "subscribe-slide-dashboard",
		"data":        data,
		"participant": participant,
	})
}

// ---- отправка ----
//
// Канал — modify-objects. Создаём текстовый объект с payload в value.
// Сервер broadcast'ит как server-modify-objects, который обрабатывается
// в handleServerModifyObjects.
//
// Every write is paced, recorded in flight and answered by the board with an
// ack carrying its id. A RATE_LIMITED ack puts the object back in the writer, so
// a refusal costs time and never the payload. Protocol frames are NOT paced:
// ping/pong and subscribe go out through writeRaw/writeEventObj directly,
// because a pong held behind the budget is a socket the board closes.

func (t *BoardsTransport) writerLoop(sess *boardsSession) {
	for {
		if out, ok := sess.takeRetry(); ok {
			t.writeObject(sess, out)
			continue
		}
		select {
		case <-t.done:
			return
		case <-sess.stop:
			return
		case <-sess.retryCh:
			// A refusal queued a retry while the fresh queue was empty.
		case pkt := <-sess.Queue:
			t.writeObject(sess, boardsOutgoing{pkt: pkt})
		}
	}
}

// writeObject waits for its slot in the board's write budget and writes one
// object. The pace is a slot clock (boardsPacer), not a sleep per packet: while
// the queue is busy the wait is whatever is left of the interval.
func (t *BoardsTransport) writeObject(sess *boardsSession, out boardsOutgoing) {
	if !t.waitSlot() {
		return
	}
	if err := t.sendObject(sess, out); err != nil {
		utils.Debugf("[BOARDS] notify-position: %v", err)
		return
	}
	// A retry re-sends bytes the tunnel already counted once.
	if out.drop == nil && out.tries == 0 {
		t.RecordSend(len(out.pkt))
	}
}

// waitSlot blocks until the next place in the board's write budget is ours.
// False means the transport is closing.
func (t *BoardsTransport) waitSlot() bool {
	d := t.pacer.reserve()
	if d <= 0 {
		return true
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-t.done:
		return false
	case <-timer.C:
		return true
	}
}

// sendObject writes one unit of work and records it in flight under its ack id.
// The payload object itself is built exactly as before (buildModifyObjects), so
// nothing changes on the wire apart from the pace.
func (t *BoardsTransport) sendObject(sess *boardsSession, out boardsOutgoing) error {
	var event map[string]interface{}
	if out.drop != nil {
		event = out.drop
	} else {
		// Уникальный ID объекта и случайные координаты.
		event = buildModifyObjects(base64.StdEncoding.EncodeToString(out.pkt), randomHex(32),
			mrand.Intn(2000), mrand.Intn(1200), *sess.creatorHash.Load(), *sess.participant.Load())
	}
	out.at = time.Now()
	id := sess.nextAck()
	if evicted := sess.trackPending(id, out); len(evicted) > 0 {
		// The board is not acknowledging: the oldest writes lost their slot to
		// keep the phone out of OOM. Counted, not silent.
		t.statOverload.Add(int64(len(evicted)))
		if utils.Throttled("boards.overload", 30*time.Second) {
			utils.Infof("[BOARDS] %d writes evicted unacknowledged (in-flight cap %d writes / %d KiB)",
				len(evicted), boardsMaxPending, boardsMaxPendingBytes/1024)
		}
	}
	if err := sess.writeEventWithAck(id, "dashboard", event); err != nil {
		sess.forgetPending(id)
		return err
	}
	return nil
}

// buildDropObjects is the drop-objects event that deletes the objects the peer
// sent. It is built once per coalesced batch (see takeDrops) because its size is
// what decides how many objects fit.
func buildDropObjects(objects []map[string]interface{}, participant string) map[string]interface{} {
	return map[string]interface{}{
		"action": "drop-objects",
		"data": map[string]interface{}{
			"objects": objects,
		},
		"participant": participant,
	}
}

// dropLoop turns a burst of received objects into one drop-objects event per
// window. Each server-modify-objects used to spawn its own goroutine with a
// 100 ms sleep, so every received object cost its own event from the same
// per-second budget as the payload that carries user traffic.
func (t *BoardsTransport) dropLoop(sess *boardsSession) {
	for {
		select {
		case <-t.done:
			return
		case <-sess.stop:
			return
		case <-sess.dropCh:
		}
		// Wait the window out so a burst becomes one event.
		select {
		case <-t.done:
			return
		case <-sess.stop:
			return
		case <-time.After(boardsDropWindow):
		}
		for {
			event, ok := sess.takeDrops(*sess.participant.Load())
			if !ok {
				break
			}
			if !t.waitSlot() {
				return
			}
			if err := t.sendObject(sess, boardsOutgoing{drop: event}); err != nil {
				utils.Debugf("[BOARDS] drop-objects: %v", err)
				return
			}
		}
	}
}

// buildModifyObjects is the modify-objects event carrying one packet as a
// text object whose value is the base64 payload. Pure, and exported through
// BoardsModifyObjects so the JS port can be compared with it byte for byte.
func buildModifyObjects(b64, objID string, x, y int, creator, participant string) map[string]interface{} {
	return map[string]interface{}{
		"action": "modify-objects",
		"data": map[string]interface{}{
			"objects": []map[string]interface{}{
				{
					"_attributes_": map[string]interface{}{
						"id":          objID,
						"value":       b64, // payload как base64
						"style":       "text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=1;",
						"vertex":      "1",
						"type":        "textbox",
						"creatorHash": creator,
						"parent":      "DASHBOARD",
						"index":       "1",
					},
					"mxGeometry": []map[string]interface{}{
						{
							"_attributes_": map[string]interface{}{
								"x":      fmt.Sprintf("%d", x),
								"y":      fmt.Sprintf("%d", y),
								"width":  "1",
								"height": "1",
								"as":     "geometry",
							},
						},
					},
					"hash": objID,
				},
			},
			"valueChanges": map[string]bool{
				objID: true,
			},
		},
		"participant": participant,
	}
}

// BoardsModifyObjects is the JSON of the dashboard event buildModifyObjects
// makes: ["dashboard", {...}], as it goes on the wire after the "42<ack>"
// prefix.
func BoardsModifyObjects(b64, objID string, x, y int, creator, participant string) []byte {
	body, _ := json.Marshal([]interface{}{"dashboard", buildModifyObjects(b64, objID, x, y, creator, participant)})
	return body
}

// ---- keepalive ----

func (t *BoardsTransport) keepAliveLoop(sess *boardsSession, stop chan struct{}) {
	tick := time.NewTicker(boardsPingInterval)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.done:
			return
		case <-tick.C:
			obj := map[string]interface{}{
				"action":      "heartbeat",
				"data":        map[string]interface{}{},
				"participant": *sess.participant.Load(),
			}
			if err := sess.writeEventObj("dashboard", obj); err != nil {
				utils.Debugf("[BOARDS] heartbeat: %v", err)
				return
			}
		}
	}
}

// noteRate reports a change of the write pace once. A refusal names the new
// rate; a climb is named only when it reaches the ceiling again. Anything more
// talkative would reproduce the log storm this replaces - dozens of
// RATE_LIMITED acks a second, one debug line each.
func (t *BoardsTransport) noteRate(rate float64, reason string) {
	t.rateLogMu.Lock()
	defer t.rateLogMu.Unlock()
	n := int(rate)
	if reason != "" {
		if n == t.rateLogged {
			return
		}
		t.rateLogged = n
		t.rateCeiling = false
		utils.Infof("[BOARDS] write rate -> %d/s (%s)", n, reason)
		return
	}
	if rate < boardsRateMax || t.rateCeiling {
		return
	}
	t.rateCeiling = true
	t.rateLogged = n
	utils.Infof("[BOARDS] write rate -> %d/s (recovered)", n)
}

// statsLoop prints one line per interval with the write counters: a rate limit
// shows up as refused/retried next to the pace, so no line per ack is needed.
func (t *BoardsTransport) statsLoop(sess *boardsSession) {
	tick := time.NewTicker(boardsStatsEvery)
	defer tick.Stop()
	for {
		select {
		case <-t.done:
			return
		case <-sess.stop:
			return
		case <-tick.C:
		}
		t.requeueExpired(sess)
		utils.Debugf("[BOARDS] write rate=%.1f/s sent=%d retried=%d refused=%d too_large=%d key_mismatch=%d dropped_dead=%d overload=%d drops=%d queue=%d pending=%d/%dKiB",
			t.pacer.currentRate(), t.statSent.Load(), t.statRetried.Load(), t.statRefused.Load(),
			t.statTooLarge.Load(), t.statKeyMismatch.Load(), t.statDroppedDead.Load(),
			t.statOverload.Load(), t.statDropEvents.Load(), len(sess.Queue),
			sess.pendingCount(), sess.pendingByteCount()/1024)
	}
}

// requeueExpired retries a write whose ack never came. The board either dropped
// the event silently or the ack was lost; keeping it would leak up to 48 KiB per
// object, and resending is the same bet the tunnel makes on any lost frame.
func (t *BoardsTransport) requeueExpired(sess *boardsSession) {
	for _, out := range sess.expiredPending(time.Now(), boardsAckTimeout) {
		if out.drop != nil {
			// A drop nobody acknowledged is not resent: it costs a full-size event
			// and an object left on the board is clutter, not lost payload.
			continue
		}
		if out.tries >= boardsMaxTries {
			t.statDroppedDead.Add(1)
			if utils.Throttled("boards.deadwrite", 30*time.Second) {
				utils.Infof("[BOARDS] write gave up after %d tries (no ack), counted as dropped_dead", out.tries)
			}
			continue
		}
		t.statRetried.Add(1)
		sess.retryObject(out)
	}
}

// ---- приём ----

func (t *BoardsTransport) handleMessage(sess *boardsSession, raw []byte) {
	if len(raw) == 1 && raw[0] == '2' {
		utils.Debugf("[BOARDS] ping -> pong")
		_ = sess.writeRaw("3")
		return
	}
	if len(raw) == 1 && raw[0] == '3' {
		return
	}

	if bytes.HasPrefix(raw, []byte("43")) {
		// handle431 runs for every "43..." packet, with or without an id: the
		// board's snapshot there is the only source of creatorHash and
		// dashboard_link, and the handshake waits for it.
		t.handle431(sess, raw)
		t.handleAck(sess, raw)
		return
	}
	if !bytes.HasPrefix(raw, []byte("42[")) {
		return
	}
	idx := bytes.IndexByte(raw, '[')
	if idx < 0 {
		return
	}
	var arr []json.RawMessage
	if err := json.Unmarshal(raw[idx:], &arr); err != nil {
		return
	}
	if len(arr) < 2 {
		return
	}

	var envelope struct {
		Action      string          `json:"action"`
		Data        json.RawMessage `json:"data"`
		Participant string          `json:"participant"`
	}
	if err := json.Unmarshal(arr[1], &envelope); err != nil {
		return
	}

	switch envelope.Action {
	case "participant-connected":
		t.handleParticipantConnected(sess, envelope.Data)
	case "server-modify-objects", "modify-objects":
		t.handleServerModifyObjects(sess, envelope.Data, envelope.Action)
	}
}

func (t *BoardsTransport) handleParticipantConnected(sess *boardsSession, raw json.RawMessage) {
	var d struct {
		Participant struct {
			Hash    string `json:"hash"`
			Session string `json:"session"`
			Name    string `json:"name"`
		} `json:"participant"`
	}
	if err := json.Unmarshal(raw, &d); err != nil {
		return
	}
	utils.Debugf("[BOARDS] participant-connected: name=%q hash=%s session=%s",
		d.Participant.Name, shortStr(d.Participant.Hash, 8), shortStr(d.Participant.Session, 8))

	if d.Participant.Session != "" && sess.Info.session == "" {
		sess.Info.session = d.Participant.Session
	}

	if d.Participant.Name == sess.Info.name && d.Participant.Hash != "" {
		h := d.Participant.Hash
		sess.creatorHash.Store(&h)
		utils.Debugf("[BOARDS] creatorHash from participant-connected: %s", shortStr(h, 8))
	}
}

func (t *BoardsTransport) handleServerModifyObjects(sess *boardsSession, raw json.RawMessage, action string) {
	payloads, toDelete := ModifyObjectsPayloads(raw, *sess.participant.Load(), sess.Info.userHash, sess.Info.name)

	for _, decoded := range payloads {
		utils.Debugf("[BOARDS<-] %s pktlen=%d", action, len(decoded))
		t.RecordReceive(len(decoded))
		t.onDataMu.RLock()
		cb := t.onData
		t.onDataMu.RUnlock()
		if cb != nil {
			cb(decoded)
		}
	}

	// Удаляем принятые объекты. The coalescer answers a whole burst with one
	// drop-objects event: the old path spawned a goroutine with a 100 ms sleep
	// per received object, and each of those events cost a slot in the same
	// per-second budget as the payload.
	sess.queueDrops(toDelete)
}

// ModifyObjectsPayloads reads a (server-)modify-objects event: the packets
// carried by objects that are not our own echo (decoded, in order), and the
// drop-objects entries that delete exactly those objects from the board.
// Pure, and exported so the JS port can be compared with it.
func ModifyObjectsPayloads(raw json.RawMessage, myPart, myUser, myName string) (payloads [][]byte, toDelete []map[string]interface{}) {
	var d struct {
		Dashboard string `json:"dashboard"`
		Name      string `json:"name"`
		Session   string `json:"session"`
		Objects   []struct {
			Attributes map[string]interface{}   `json:"_attributes_"`
			Hash       string                   `json:"hash"`
			Geometry   []map[string]interface{} `json:"mxGeometry"`
		} `json:"objects"`
	}
	if err := json.Unmarshal(raw, &d); err != nil {
		return nil, nil
	}
	for _, o := range d.Objects {
		val, _ := o.Attributes["value"].(string)
		if val == "" {
			continue
		}
		creator, _ := o.Attributes["creatorHash"].(string)

		if creator != "" && (creator == myPart || creator == myUser) {
			continue // own echo
		}
		if d.Name != "" && d.Name == myName {
			continue // own echo
		}

		decoded, err := base64.StdEncoding.DecodeString(val)
		if err != nil || len(decoded) == 0 {
			continue
		}
		payloads = append(payloads, decoded)

		// Собираем объект для удаления
		toDelete = append(toDelete, map[string]interface{}{
			"_attributes_": o.Attributes,
			"mxGeometry":   o.Geometry,
			"hash":         o.Hash,
		})
	}
	return payloads, toDelete
}

func (t *BoardsTransport) handle431(sess *boardsSession, raw []byte) {
	idx := bytes.IndexByte(raw, '[')
	if idx < 0 {
		return
	}
	body := raw[idx:]
	if !bytes.Contains(body, []byte(`"dashboard_link"`)) &&
		!bytes.Contains(body, []byte(`"participantHash"`)) &&
		!bytes.Contains(body, []byte(`"creatorHash"`)) {
		return
	}
	var arr []json.RawMessage
	if err := json.Unmarshal(body, &arr); err != nil {
		return
	}
	if len(arr) == 0 {
		return
	}
	var snap struct {
		Participant struct {
			Hash            string `json:"hash"`
			Session         string `json:"session"`
			ParticipantHash string `json:"participantHash"`
			CreatorHash     string `json:"creatorHash"`
			DashboardLink   struct {
				Session   string `json:"session"`
				Dashboard string `json:"dashboard"`
			} `json:"dashboard_link"`
		} `json:"participant"`
	}
	if err := json.Unmarshal(arr[0], &snap); err != nil {
		return
	}

	if snap.Participant.Session != "" && sess.Info.session == "" {
		sess.Info.session = snap.Participant.Session
	}
	if snap.Participant.DashboardLink.Session != "" && sess.Info.session == "" {
		sess.Info.session = snap.Participant.DashboardLink.Session
	}
	if snap.Participant.DashboardLink.Dashboard != "" && sess.Info.dashboard == "" {
		sess.Info.dashboard = snap.Participant.DashboardLink.Dashboard
		sess.Info.currentSlide = sess.Info.dashboard
		utils.Debugf("[BOARDS] dashboard from 431: %s", shortStr(sess.Info.dashboard, 8))
	}

	if snap.Participant.CreatorHash != "" {
		ch := snap.Participant.CreatorHash
		sess.creatorHash.Store(&ch)
		utils.Debugf("[BOARDS] creatorHash from 431: %s", shortStr(ch, 8))
	}
	if snap.Participant.ParticipantHash != "" {
		ch := snap.Participant.ParticipantHash
		sess.creatorHash.Store(&ch)
		utils.Debugf("[BOARDS] creatorHash from 431/participantHash: %s", shortStr(ch, 8))
	}
}

// jar returns the transport's shared cookie jar (never nil).
func (t *BoardsTransport) jar() *cookiejar.Jar {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	if jar == nil {
		jar, _ = cookiejar.New(nil)
		t.jarMu.Lock()
		t.cookieJar = jar
		t.jarMu.Unlock()
	}
	return jar
}

// ---- CookieExchanger ----

// FetchCookies returns a snapshot of the transport's current cookie jar as
// name -> value for boards.yandex.ru.
func (t *BoardsTransport) FetchCookies() (map[string]string, error) {
	u, err := url.Parse("https://" + boardsBase + "/")
	if err != nil {
		return nil, err
	}
	out := make(map[string]string)
	for _, c := range t.jar().Cookies(u) {
		out[c.Name] = c.Value
	}
	return out, nil
}

// ApplyCookies replaces the transport's cookie jar and forces the current WS
// session to reconnect.
func (t *BoardsTransport) ApplyCookies(values map[string]string) error {
	if len(values) == 0 {
		return nil
	}
	u, _ := url.Parse("https://" + boardsBase + "/")
	jar, _ := cookiejar.New(nil)
	cookies := make([]*http.Cookie, 0, len(values))
	for k, v := range values {
		cookies = append(cookies, &http.Cookie{Name: k, Value: v, Path: "/"})
	}
	jar.SetCookies(u, cookies)

	t.jarMu.Lock()
	t.cookieJar = jar
	t.jarMu.Unlock()

	utils.Debugf("[BOARDS] applied %d cookies, forcing reconnect", len(cookies))

	if s := t.session.Load(); s != nil && s.Conn != nil {
		_ = s.Conn.Close()
	}
	return nil
}

// boardsGuestLanding reports whether an /api refusal means "you are not signed
// in" rather than "this board is closed". Yandex answers an anonymous info call
// with code 204 (or a redirect to /guest) and mints the guest session on the
// next call, so treating it as fatal would refuse every open board.
func boardsGuestLanding(e *boardsAPIError) bool {
	if e == nil {
		return false
	}
	if e.Code == 204 {
		return true
	}
	return strings.Contains(e.Redirect, "/guest")
}

// socketURLs lists the engine.io endpoints to try for this board, best first:
// the board's own shard host, then the endpoint that worked last time, then the
// site host.
//
// The shard goes first for a reason beyond reachability: a dashboard room is
// per socket shard. Two guests that dial the site host are load-balanced onto
// different front pods and never see each other's modify-objects — measured
// 2026-11-08, two peers on boards.yandex.ru delivered nothing in either
// direction, the same two peers on socket62.boards.yandex.ru delivered both
// ways. The shard from get-whiteboard-info is per board, so both peers of a
// tunnel pick the same one. The site host stays as a last resort for a shard
// that answers 404, which is how socket33.boards.yandex.ru behaved.
func (t *BoardsTransport) socketURLs(info boardsInfo) []string {
	urls := make([]string, 0, 3)
	addURL := func(u string) {
		if u == "" {
			return
		}
		for _, have := range urls {
			if have == u {
				return
			}
		}
		urls = append(urls, u)
	}
	addHost := func(host string) {
		if host == "" {
			return
		}
		addURL(fmt.Sprintf("wss://%s/socket.io/?EIO=4&transport=websocket", host))
	}
	addHost(info.wsHost)
	if prev, ok := t.goodSocketURL.Load().(string); ok {
		addURL(prev)
	}
	addHost(boardsBase)
	return urls
}
