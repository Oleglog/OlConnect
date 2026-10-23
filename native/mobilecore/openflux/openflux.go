package openflux

import (
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"

	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/netbind"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport/avito"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport/bitrix24"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport/cupsonline"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport/mailru"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/transport/yandex"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/utils"
)

// SocketProtector is implemented on the Android side by VpnService.protect(fd).
type SocketProtector interface {
	Protect(fd int) bool
}

// SetProtector registers the Android VpnService socket protector so all
// outbound OpenFlux TCP/UDP/WebRTC sockets bypass the VPN tunnel.
func SetProtector(p SocketProtector) {
	if p == nil {
		netbind.SetProtector(nil)
		return
	}
	netbind.SetProtector(p.Protect)
}

var (
	client         = packetClient{}
	encKeyMu       sync.Mutex
	encryptionKey  string
	codecMu        sync.Mutex
	currentCodec   = "batched"
	cookiesMu      sync.Mutex
	sessionCookies string
)

// SetCookies stores raw session cookies ("name=value; ..." or Netscape
// cookies.txt content) applied to Mail.ru / Yandex transports on Start.
func SetCookies(cookies string) {
	cookiesMu.Lock()
	defer cookiesMu.Unlock()
	sessionCookies = strings.TrimSpace(cookies)
}

// SetEncryptionKey configures an optional AES-256-GCM transport key.
func SetEncryptionKey(key string) {
	encKeyMu.Lock()
	defer encKeyMu.Unlock()
	encryptionKey = strings.TrimSpace(key)
}

// SetCodec configures the wire format: "batched" (coalesced zstd) or "legacy" (per-packet LZ4).
func SetCodec(codec string) {
	codecMu.Lock()
	defer codecMu.Unlock()
	switch strings.ToLower(strings.TrimSpace(codec)) {
	case "legacy":
		currentCodec = "legacy"
	default:
		currentCodec = "batched"
	}
}

type packetClient struct {
	mu        sync.Mutex
	running   bool
	transport transport.Transport
	packets   [][]byte
	logs      []string
}

func appendLog(message string) {
	client.mu.Lock()
	defer client.mu.Unlock()
	client.logs = append(client.logs, message)
	if len(client.logs) > 500 {
		client.logs = append([]string(nil), client.logs[len(client.logs)-500:]...)
	}
}

func detectTransport(docURL string) string {
	if strings.Contains(docURL, "mail.ru") {
		return "mailru"
	}
	if strings.Contains(docURL, "avito.ru") {
		return "avito"
	}
	if strings.Contains(docURL, "cups.online") {
		return "cupsonline"
	}
	if strings.Contains(docURL, "telemost.yandex.ru") {
		return "telemost"
	}
	if strings.Contains(docURL, "bitrix24") {
		return "bitrix24"
	}
	if !strings.Contains(docURL, "://") && strings.HasPrefix(strings.TrimSpace(docURL), "Wy") {
		return "cupsonline"
	}

	// Volga is strictly used on /edit/d/ documents
	if strings.Contains(docURL, "/edit/d/") {
		return "vyandex"
	}
	return "yandex"
}

func encryptionContext(detected, docURL string) string {
	switch detected {
	case "cupsonline", "cups":
		return "cupsonline"
	case "avito":
		return "avito"
	}
	return docURL
}

func Start(documentURL string, transportType string) string {
	if documentURL == "" {
		return "Ссылка на документ не указана"
	}

	client.mu.Lock()
	if client.running {
		prev := client.transport
		client.running = false
		client.transport = nil
		client.packets = nil
		client.mu.Unlock()
		if prev != nil {
			_ = prev.Stop()
		}
		client.mu.Lock()
	}
	client.running = true
	client.packets = nil
	client.logs = nil
	client.mu.Unlock()

	utils.EnableDebug()
	utils.SetLogSink(appendLog)

	config := transport.DefaultConfig()
	detected := transportType
	if detected == "" || detected == "auto" {
		detected = detectTransport(documentURL)
	}

	var innerTrans transport.Transport
	switch detected {
	case "mailru":
		appendLog("[ANDROID] Запуск транспорта Mail.ru Docs")
		mr := mailru.NewMailruDocsTransport(documentURL, config)
		applySessionCookiesForDomain(mr, "mail.ru")
		innerTrans = mr
	case "avito":
		appendLog("[ANDROID] Обнаружен сервис Avito. Запуск транспорта avito")
		innerTrans = transport.NewStripedRoomTransport(avito.NewAvitoTransport(documentURL, config, sessionCookieHeaderForDomain("avito.ru"), true))
	case "cupsonline", "cups":
		appendLog("[ANDROID] Обнаружен сервис Cups.online. Запуск транспорта cupsonline")
		innerTrans = transport.NewStripedRoomTransport(cupsonline.NewCupsonlineTransport(documentURL, config, true))
	case "telemost":
		appendLog("[ANDROID] Обнаружена конференция Telemost. Запуск транспорта telemost")
		innerTrans = yandex.NewStripedTelemostTransport(sessionCookieHeaderForDomain("yandex.ru"), documentURL, false, config)
	case "bitrix24", "b24":
		appendLog("[ANDROID] Обнаружен сервис Bitrix24. Запуск транспорта bitrix24")
		innerTrans = bitrix24.NewBitrix24Transport(documentURL, config, sessionCookieHeaderForDomain("bitrix"), os.Getenv("B24_SESSID"), os.Getenv("B24_CHAT_ID"))
	case "vyandex", "volga":
		appendLog("[ANDROID] Обнаружен редактор Volga. Запуск транспорта vyandex")
		vt := yandex.NewYandexVolgaTransportWithConfig(documentURL, config, yandex.SlimVolgaConfig())
		applySessionCookiesForDomain(vt, "yandex.ru")
		innerTrans = vt
	default:
		appendLog("[ANDROID] Запуск классического транспорта yandex")
		yt := yandex.NewYandexDocsTransport(documentURL, config)
		applySessionCookiesForDomain(yt, "yandex.ru")
		innerTrans = yt
	}

	encKeyMu.Lock()
	key := encryptionKey
	encKeyMu.Unlock()
	if envKey := os.Getenv("OPENFLUX_ENCRYPTION_KEY"); envKey != "" {
		key = strings.TrimSpace(envKey)
	} else if envKey := os.Getenv("OPENFLUX_KEY"); envKey != "" {
		key = strings.TrimSpace(envKey)
	}
	if key != "" {
		appendLog("[ANDROID] Включение сквозного шифрования AES-256-GCM")
		encTrans, err := transport.NewEncryptedTransport(innerTrans, key, encryptionContext(detected, documentURL), false)
		if err != nil {
			appendLog(fmt.Sprintf("[ANDROID] Ошибка настройки шифрования: %v", err))
			client.mu.Lock()
			client.running = false
			client.mu.Unlock()
			return err.Error()
		}
		innerTrans = encTrans
	}

	codecMu.Lock()
	codec := currentCodec
	codecMu.Unlock()

	var trans transport.Transport
	if codec == "legacy" || os.Getenv("OPENFLUX_CODEC") == "legacy" {
		appendLog("[ANDROID] Использование legacy LZ4 кодека")
		trans = transport.NewCompressedTransport(innerTrans)
	} else {
		appendLog("[ANDROID] Использование batched+zstd кодека")
		trans = transport.NewBatchedTransport(innerTrans)
	}
	trans.Receive(func(data []byte) {
		packet := append([]byte(nil), data...)
		client.mu.Lock()
		if !client.running {
			client.mu.Unlock()
			return
		}
		if len(client.packets) >= config.MaxQueueSize {
			client.packets = client.packets[1:]
		}
		client.packets = append(client.packets, packet)
		client.mu.Unlock()
	})

	if err := trans.Start(); err != nil {
		appendLog(fmt.Sprintf("[ANDROID] Ошибка запуска: %v", err))
		client.mu.Lock()
		client.running = false
		client.mu.Unlock()
		return err.Error()
	}

	client.mu.Lock()
	client.transport = trans
	client.mu.Unlock()
	return ""
}

// sessionCookieHeader renders the stored session cookies as a raw Cookie
// header value ("name=value; ...") for transports that take cookies as a
// plain string (avito, telemost, bitrix24). Netscape cookies.txt lines are
// reduced to their name=value pairs.
func sessionCookieHeader() string {
	return sessionCookieHeaderForDomain("")
}

func sessionCookieHeaderForDomain(domainFilter string) string {
	cookiesMu.Lock()
	raw := sessionCookies
	cookiesMu.Unlock()
	if strings.TrimSpace(raw) == "" {
		return ""
	}
	parsed := parseSessionCookiesForDomain(raw, domainFilter)
	parts := make([]string, 0, len(parsed))
	for name, value := range parsed {
		parts = append(parts, name+"="+value)
	}
	sort.Strings(parts)
	return strings.Join(parts, "; ")
}

// cookieApplier is implemented by transports with a cookie jar (mailru, yandex).
type cookieApplier interface {
	ApplyCookies(values map[string]string) error
}

// applySessionCookies parses SetCookies content (either "name=value; ..."
// pairs or Netscape cookies.txt lines) and pushes matching-domain cookies
// into the transport. Unknown/foreign domains are ignored by each parser.
func applySessionCookies(t cookieApplier) {
	applySessionCookiesForDomain(t, "")
}

func applySessionCookiesForDomain(t cookieApplier, domainFilter string) {
	cookiesMu.Lock()
	raw := sessionCookies
	cookiesMu.Unlock()
	if strings.TrimSpace(raw) == "" {
		return
	}
	values := parseSessionCookiesForDomain(raw, domainFilter)
	if len(values) == 0 {
		appendLog("[ANDROID] Куки заданы, но ни одной пары name=value не распознано")
		return
	}
	if err := t.ApplyCookies(values); err != nil {
		appendLog(fmt.Sprintf("[ANDROID] Ошибка применения кук: %v", err))
		return
	}
	appendLog(fmt.Sprintf("[ANDROID] Применено кук сессии: %d", len(values)))
}

// parseSessionCookies accepts "a=b; c=d" and Netscape cookies.txt lines
// (domain\t...\tname\tvalue), including #HttpOnly_ rows.
func parseSessionCookies(raw string) map[string]string {
	return parseSessionCookiesForDomain(raw, "")
}

func parseSessionCookiesForDomain(raw, domainFilter string) map[string]string {
	domainFilter = strings.ToLower(strings.TrimSpace(domainFilter))
	parse := func(filter string) map[string]string {
		out := make(map[string]string)
		for _, line := range strings.Split(raw, "\n") {
			line = strings.TrimSpace(strings.TrimSuffix(line, "\r"))
			lower := strings.ToLower(line)
			if strings.HasPrefix(lower, "#httponly_") {
				line = line[len("#httponly_"):]
			}
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			fields := strings.Split(line, "\t")
			if len(fields) >= 7 {
				rawDom := fields[0]
				if strings.HasPrefix(strings.ToLower(rawDom), "#httponly_") {
					rawDom = rawDom[len("#httponly_"):]
				}
				dom := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(rawDom), "."))
				if filter != "" && !strings.Contains(dom, filter) {
					continue
				}
				name := strings.TrimSpace(fields[5])
				value := strings.TrimSpace(fields[6])
				if name != "" && value != "" {
					out[name] = value
				}
				continue
			}
			rawLine := line
			if strings.HasPrefix(strings.ToLower(rawLine), "cookie:") {
				rawLine = strings.TrimSpace(rawLine[len("cookie:"):])
			}
			for _, part := range strings.Split(rawLine, ";") {
				part = strings.TrimSpace(part)
				if part == "" || strings.HasPrefix(part, "#") {
					continue
				}
				kv := strings.SplitN(part, "=", 2)
				if len(kv) != 2 {
					continue
				}
				name := strings.TrimSpace(kv[0])
				value := strings.TrimSpace(kv[1])
				if name != "" && value != "" {
					out[name] = value
				}
			}
		}
		return out
	}
	if domainFilter != "" {
		if filtered := parse(domainFilter); len(filtered) > 0 {
			return filtered
		}
	}
	return parse("")
}

func Stop() {
	client.mu.Lock()
	trans := client.transport
	client.running = false
	client.transport = nil
	client.packets = nil
	client.mu.Unlock()
	appendLog("[ANDROID] Остановка транспорта")
	if trans != nil {
		_ = trans.Stop()
	}
}

func IsConnected() bool {
	client.mu.Lock()
	trans := client.transport
	client.mu.Unlock()
	return trans != nil && trans.IsConnected()
}

func Send(packet []byte) string {
	client.mu.Lock()
	trans := client.transport
	running := client.running
	client.mu.Unlock()
	if !running || trans == nil {
		return "Транспорт не запущен"
	}
	if err := trans.Send(packet); err != nil {
		return err.Error()
	}
	return ""
}

func Read() []byte {
	client.mu.Lock()
	defer client.mu.Unlock()
	if len(client.packets) == 0 {
		return nil
	}
	packet := client.packets[0]
	client.packets = client.packets[1:]
	return packet
}

func ReadLogs() string {
	client.mu.Lock()
	defer client.mu.Unlock()
	logs := strings.Join(client.logs, "\n")
	client.logs = nil
	return logs
}
