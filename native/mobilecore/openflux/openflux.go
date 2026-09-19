package openflux

import (
	"fmt"
	"strings"
	"sync"
)

var (
	client        = packetClient{}
	encKeyMu      sync.Mutex
	encryptionKey string
)

// SetEncryptionKey configures an optional AES-256-GCM transport key.
func SetEncryptionKey(key string) {
	encKeyMu.Lock()
	defer encKeyMu.Unlock()
	encryptionKey = strings.TrimSpace(key)
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
		client.mu.Unlock()
		return ""
	}
	client.running = true
	client.packets = nil
	client.logs = nil
	client.mu.Unlock()

	EnableDebug()
	SetLogSink(appendLog)

	config := DefaultTransportConfig()
	detected := transportType
	if detected == "" || detected == "auto" {
		detected = detectTransport(documentURL)
	}

	var innerTrans Transport
	if detected == "vyandex" {
		appendLog("[ANDROID] Обнаружен редактор Volga. Запуск транспорта vyandex")
		innerTrans = NewYandexVolgaTransport(documentURL, config)
	} else {
		appendLog("[ANDROID] Запуск классического транспорта yandex")
		innerTrans = NewYandexDocsTransport(documentURL, config)
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

	trans := newCompressedTransport(innerTrans)
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
