// Package openflux implements a transport that tunnels packets through
// Mail.ru's cloud document editor (docs.datacloudmail.ru), the same
// coauthoring backend family as Yandex.Docs. Two peers open the same
// public document and smuggle packets through the "cursor" field of the
// collaborative editing protocol.
package openflux

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const mailruUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

var (
	mailruCursorPayloadRe = regexp.MustCompile(`\\?"cursor\\?":\\?"[^;]+;([^"\\]+)\\?"`)
	mailruExcelPayloadRe  = regexp.MustCompile(`\\?"excelAdditionalInfo\\?":\\?"([^"\\]+)\\?"`)
)

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
	ApiBase      string
}

type MailruDocSession struct {
	Info       MailruDocsInfo
	Conn       *websocket.Conn
	WriteQueue chan []byte
	UserID     string
	writeMu    sync.Mutex
}

func (s *MailruDocSession) safeWrite(messageType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if s.Conn == nil {
		return fmt.Errorf("connection closed")
	}
	return s.Conn.WriteMessage(messageType, data)
}

type MailruDocsTransport struct {
	*BaseTransport

	weblink string
	session *MailruDocSession

	userCounter atomic.Int32
	baseUserID  string
}

// NewMailruDocsTransport accepts either a bare weblink ("AbCdEfGh1/IjKlMnOp2")
// or a full public URL ("https://cloud.mail.ru/public/AbCdEfGh1/IjKlMnOp2"),
// normalizing the latter to the former.
func NewMailruDocsTransport(weblink string, config TransportConfig) *MailruDocsTransport {
	t := &MailruDocsTransport{
		BaseTransport: NewBaseTransport(config),
		weblink:       normalizeMailruWeblink(weblink),
	}
	t.baseUserID = randMailruUserID()
	return t
}

func normalizeMailruWeblink(weblink string) string {
	weblink = strings.TrimSpace(weblink)
	if idx := strings.IndexAny(weblink, "?#"); idx != -1 {
		weblink = weblink[:idx]
	}
	weblink = strings.TrimRight(weblink, "/")

	if idx := strings.Index(weblink, "/public/"); idx != -1 {
		return strings.Trim(weblink[idx+len("/public/"):], "/")
	}

	for _, prefix := range []string{
		"https://cloud.mail.ru/public/",
		"http://cloud.mail.ru/public/",
		"https://doc.mail.ru/public/",
		"http://doc.mail.ru/public/",
		"https://docs.mail.ru/public/",
		"http://docs.mail.ru/public/",
		"https://doc.mail.ru/d/",
		"http://doc.mail.ru/d/",
		"https://doc.mail.ru/",
		"http://doc.mail.ru/",
		"https://docs.mail.ru/",
		"http://docs.mail.ru/",
		"https://cloud.mail.ru/",
		"http://cloud.mail.ru/",
		"/public/",
		"public/",
	} {
		if strings.HasPrefix(weblink, prefix) {
			return strings.Trim(strings.TrimPrefix(weblink, prefix), "/")
		}
	}
	return strings.Trim(weblink, "/")
}

func (t *MailruDocsTransport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}

	t.baseUserID = randMailruUserID()
	SafeGo("mailru.keepAlive", t.keepAliveLoop)
	t.connectToDoc(0)

	return nil
}

func (t *MailruDocsTransport) Send(data []byte) error {
	if !t.IsConnected() {
		return fmt.Errorf("transport not connected")
	}

	t.Mu.RLock()
	session := t.session
	t.Mu.RUnlock()

	if session == nil {
		return fmt.Errorf("no active session")
	}

	select {
	case session.WriteQueue <- data:
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

	Debugf("[M-DOCS] connectToDoc attempt %d", attempt)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				Debugf("[PANIC] recovered in mailru.connect: %v", r)
			}
		}()
		t.Mu.Lock()
		existingSession := t.session
		t.Mu.Unlock()

		var userID string
		if existingSession != nil {
			userID = existingSession.UserID
		} else {
			suffix := fmt.Sprintf("%03d", t.userCounter.Add(1)%1000)
			userID = t.baseUserID + suffix
		}

		info, err := t.fetchDocInfo(t.weblink)
		if err != nil {
			Debugf("[M-DOCS] fetchDocInfo failed: %v", err)
			t.scheduleReconnect(attempt)
			return
		}

		dialer := websocket.Dialer{
			HandshakeTimeout: 15 * time.Second,
			NetDialContext: (&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		}
		headers := http.Header{}
		headers.Set("User-Agent", mailruUserAgent)
		origin := "https://docs.datacloudmail.ru"
		if info.ApiBase != "" {
			if u, err := url.Parse(info.ApiBase); err == nil && u.Scheme != "" && u.Host != "" {
				origin = fmt.Sprintf("%s://%s", u.Scheme, u.Host)
			}
		}
		headers.Set("Origin", origin)

		Debugf("[M-DOCS] WebSocket dial %s (origin: %s)", info.WsURL, origin)
		conn, resp, err := dialer.Dial(info.WsURL, headers)
		if err != nil {
			status := 0
			if resp != nil {
				status = resp.StatusCode
			}
			Debugf("[M-DOCS] WebSocket dial failed (http %d): %v", status, err)
			t.scheduleReconnect(attempt)
			return
		}
		Debugf("[M-DOCS] WebSocket connected, handshaking Engine.IO...")

		writeQueue := make(chan []byte, t.GetConfig().MaxQueueSize)
		if existingSession != nil {
			writeQueue = existingSession.WriteQueue
		}

		session := &MailruDocSession{
			Info:       info,
			Conn:       conn,
			WriteQueue: writeQueue,
			UserID:     userID,
		}

		t.Mu.Lock()
		t.session = session
		t.Mu.Unlock()

		if existingSession == nil {
			SafeGo("mailru.writer", t.writerLoop)
		}

		// 1. Wait for Engine.IO open frame: 0{"sid":"...", ...}
		_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
		_, openFrame, err := conn.ReadMessage()
		if err != nil {
			Debugf("[M-DOCS] Engine.IO open frame read failed: %v", err)
			conn.Close()
			t.scheduleReconnect(attempt)
			return
		}
		if !strings.HasPrefix(string(openFrame), "0") {
			Debugf("[M-DOCS] Warning: unexpected Engine.IO open frame: %s", string(openFrame))
		}

		// 2. Send Socket.IO connect frame: 40{"token":"..."}
		auth1 := fmt.Sprintf(`40{"token":"%s"}`, info.Token)
		if err := session.safeWrite(websocket.TextMessage, []byte(auth1)); err != nil {
			Debugf("[M-DOCS] Failed to send Socket.IO connect: %v", err)
			conn.Close()
			t.scheduleReconnect(attempt)
			return
		}

		// 3. Read Socket.IO connect ACK: 40{...}
		_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
		_, ackFrame, err := conn.ReadMessage()
		if err != nil {
			Debugf("[M-DOCS] Socket.IO connect ACK read failed: %v", err)
			conn.Close()
			t.scheduleReconnect(attempt)
			return
		}
		if !strings.HasPrefix(string(ackFrame), "40") {
			Debugf("[M-DOCS] Warning: expected 40 ACK, got: %s", string(ackFrame))
		}

		// 4. Send 42 auth event
		peerUserID := info.EditorUserID
		if peerUserID == "" {
			peerUserID = userID
		}

		authMsg := map[string]interface{}{
			"type":                "auth",
			"docid":               info.DocKey,
			"documentCallbackUrl": info.CallbackURL,
			"token":               "fghhfgsjdgfjs",
			"user": map[string]interface{}{
				"id":        peerUserID,
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
				"userid":          peerUserID,
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
		auth2 := fmt.Sprintf("42%s", string(messagePart))
		if err := session.safeWrite(websocket.TextMessage, []byte(auth2)); err != nil {
			Debugf("[M-DOCS] Failed to send auth message: %v", err)
			conn.Close()
			t.scheduleReconnect(attempt)
			return
		}
		_ = conn.SetReadDeadline(time.Time{})

		connectedAt := time.Now()
		for t.IsRunning() {
			_, message, err := conn.ReadMessage()
			if err != nil {
				Debugf("[M-DOCS] Read error: %v", err)
				t.SetConnected(false)
				conn.Close()

				next := attempt
				if time.Since(connectedAt) > 15*time.Second {
					next = -1
				}
				t.scheduleReconnect(next)
				return
			}
			t.handleMessage(session, message)
		}
	}()
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

		payload := base64.StdEncoding.EncodeToString(pending)
		msg := fmt.Sprintf(`42["message",{"type":"cursor","cursor":"18;%s"}]`, payload)
		if err := session.safeWrite(websocket.TextMessage, []byte(msg)); err != nil {
			Debugf("[M-DOCS] Write error: %v", err)
			time.Sleep(15 * time.Millisecond)
			continue
		}
		pending = nil
	}
}

func (t *MailruDocsTransport) keepAliveLoop() {
	ticker := time.NewTicker(t.GetConfig().KeepAliveInterval)
	defer ticker.Stop()
	keepAliveMsg := `42["message",{"type":"cursor","cursor":"18;---KA---"}]`

	for t.IsRunning() {
		<-ticker.C
		t.Mu.Lock()
		session := t.session
		t.Mu.Unlock()

		if session != nil && session.Conn != nil {
			if err := session.safeWrite(websocket.TextMessage, []byte(keepAliveMsg)); err != nil {
				Debugf("[M-DOCS] Keep-alive failed: %v", err)
				t.SetConnected(false)
			}
		}
	}
}

func (t *MailruDocsTransport) handleMessage(session *MailruDocSession, data []byte) {
	text := string(data)

	if strings.Contains(text, "---KA---") {
		return
	}

	// Socket.IO ping - respond with pong
	if text == "2" {
		if session != nil && session.Conn != nil {
			session.safeWrite(websocket.TextMessage, []byte("3"))
		}
		return
	}
	if text == "3" {
		return
	}

	if strings.Contains(text, `"type":"auth"`) {
		if strings.Contains(text, `"result":1`) {
			Debugf("[M-DOCS] Auth OK: session authenticated for user %s", session.UserID)
			t.SetConnected(true)
			return
		}
		if strings.Contains(text, `"result":0`) {
			Debugf("[M-DOCS] Auth REJECTED: %s", text)
			t.SetConnected(false)
			return
		}
	}

	if strings.Contains(text, `"type":"disconnectReason"`) {
		Debugf("[M-DOCS] Received disconnectReason: %s", text)
		t.SetConnected(false)
		return
	}

	if strings.Contains(text, "cursor") || strings.Contains(text, "saveChanges") || strings.Contains(text, "excelAdditionalInfo") {
		base64Strings := t.extractAllBase64Strings(text)
		for _, base64Str := range base64Strings {
			decoded, err := base64.StdEncoding.DecodeString(base64Str)
			if err != nil {
				Debugf("[M-DOCS] Base64 decode error: %v", err)
				continue
			}

			t.RecordReceive(len(decoded))
			t.CallReceive(decoded)
		}
	}
}

func (t *MailruDocsTransport) extractAllBase64Strings(response string) []string {
	var results []string
	if matches := mailruCursorPayloadRe.FindAllStringSubmatch(response, -1); len(matches) > 0 {
		for _, m := range matches {
			if len(m) > 1 && m[1] != "" && !strings.Contains(m[1], "---KA---") {
				results = append(results, m[1])
			}
		}
	}
	if strings.Contains(response, "saveChanges") || strings.Contains(response, "excelAdditionalInfo") {
		if matches := mailruExcelPayloadRe.FindAllStringSubmatch(response, -1); len(matches) > 0 {
			for _, m := range matches {
				if len(m) > 1 && m[1] != "" {
					results = append(results, m[1])
				}
			}
		}
	}
	return results
}

func (t *MailruDocsTransport) scheduleReconnect(attempt int) {
	next := attempt + 1
	if !t.IsRunning() || next >= t.GetConfig().MaxReconnectAttempts {
		return
	}

	d := reconnectBackoff(next)
	Debugf("[M-DOCS] reconnecting in %v (attempt %d)", d, next)
	time.Sleep(d)
	if !t.IsRunning() {
		return
	}

	t.RecordReconnect()
	t.connectToDoc(next)
}

func (t *MailruDocsTransport) fetchDocInfo(weblink string) (MailruDocsInfo, error) {
	client := &http.Client{Timeout: 15 * time.Second}

	clean := normalizeMailruWeblink(weblink)
	randomEmail := fmt.Sprintf("user_%s@mail.ru", randMailruUserID())
	reqBody := map[string]string{
		"x-email":  randomEmail,
		"public":   "/" + clean,
		"platform": "desktop_web",
	}
	jsonData, _ := json.Marshal(reqBody)

	apiURL := "https://cloud.mail.ru/api/v4/r7/edit"
	Debugf("[M-DOCS] fetchDocInfo POST %s (public: /%s, email: %s)", apiURL, clean, randomEmail)

	req, _ := http.NewRequest("POST", apiURL, bytes.NewBuffer(jsonData))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("User-Agent", mailruUserAgent)
	req.Header.Set("X-Api-Version", "4")
	req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s?weblink=%s", clean, clean))

	resp, err := client.Do(req)
	if err != nil {
		return MailruDocsInfo{}, err
	}
	defer resp.Body.Close()

	bodyBytes, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return MailruDocsInfo{}, fmt.Errorf("API returned status %d: %s", resp.StatusCode, strings.TrimSpace(string(bodyBytes)))
	}

	var res map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &res); err != nil {
		return MailruDocsInfo{}, fmt.Errorf("failed to parse JSON: %w", err)
	}

	apiBase, _ := res["api"].(string)
	token, _ := res["token"].(string)

	document, ok := res["document"].(map[string]interface{})
	if !ok || document == nil {
		return MailruDocsInfo{}, fmt.Errorf("document object missing in response")
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
		return MailruDocsInfo{}, fmt.Errorf("editorConfig object missing in response")
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
		ApiBase:      apiBase,
	}, nil
}

func randMailruUserID() string {
	return fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
}
