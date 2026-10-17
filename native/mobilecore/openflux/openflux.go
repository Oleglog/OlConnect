package openflux

import (
	"fmt"
	"os"
	"strings"
	"sync"

	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/avito"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/bitrix24"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/cupsonline"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/mailru"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/utils"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/yandex"
)

var (
	client        = packetClient{}
	encKeyMu      sync.Mutex
	encryptionKey string
	codecMu       sync.Mutex
	currentCodec  = "batched"
)

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
	transport Transport
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

	config := DefaultTransportConfig()
	detected := transportType
	if detected == "" || detected == "auto" {
		detected = detectTransport(documentURL)
	}

	var innerTrans Transport
	switch detected {
	case "mailru":
		appendLog("[ANDROID] Запуск транспорта Mail.ru Docs")
		innerTrans = mailru.NewMailruDocsTransport(documentURL, config)
	case "avito":
		appendLog("[ANDROID] Обнаружен сервис Avito. Запуск транспорта avito")
		innerTrans = avito.NewAvitoTransport(documentURL, config, "", true)
	case "cupsonline", "cups":
		appendLog("[ANDROID] Обнаружен сервис Cups.online. Запуск транспорта cupsonline")
		innerTrans = cupsonline.NewCupsonlineTransport(documentURL, config, true)
	case "telemost":
		appendLog("[ANDROID] Обнаружена конференция Telemost. Запуск транспорта telemost")
		innerTrans = yandex.NewTelemostTransport("", documentURL, false, config)
	case "bitrix24", "b24":
		appendLog("[ANDROID] Обнаружен сервис Bitrix24. Запуск транспорта bitrix24")
		innerTrans = bitrix24.NewBitrix24Transport(documentURL, config, os.Getenv("B24_COOKIES"), os.Getenv("B24_SESSID"), os.Getenv("B24_CHAT_ID"))
	case "vyandex", "volga":
		appendLog("[ANDROID] Обнаружен редактор Volga. Запуск транспорта vyandex")
		innerTrans = yandex.NewYandexVolgaTransport(documentURL, config)
	default:
		appendLog("[ANDROID] Запуск классического транспорта yandex")
		innerTrans = yandex.NewYandexDocsTransport(documentURL, config)
	}

	encKeyMu.Lock()
	key := encryptionKey
	encKeyMu.Unlock()
	if key != "" {
		appendLog("[ANDROID] Включение сквозного шифрования AES-256-GCM")
		encTrans, err := NewEncryptedTransport(innerTrans, key, documentURL, false)
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

	var trans Transport
	if codec == "legacy" {
		appendLog("[ANDROID] Использование legacy LZ4 кодека")
		trans = newCompressedTransport(innerTrans)
	} else {
		appendLog("[ANDROID] Использование batched+zstd кодека")
		trans = NewBatchedTransport(innerTrans)
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
