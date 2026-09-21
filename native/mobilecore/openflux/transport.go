package openflux

import (
	"bytes"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/pierrec/lz4/v4"
)

func detectTransport(docURL string) string {
	if strings.Contains(docURL, "mail.ru") {
		return "mailru"
	}

	httpClient := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", docURL, nil)
	if err != nil {
		return "yandex"
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
	resp, err := httpClient.Do(req)
	if err != nil {
		return "yandex"
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
	if err != nil {
		return "yandex"
	}

	content := string(body)
	if strings.Contains(content, `"officeType":"volga"`) || (strings.Contains(content, "volga") && !strings.Contains(content, "balancer_url")) {
		return "vyandex"
	}
	return "yandex"
}

const (
	minCompressSize   = 200
	compressionMarker = 0x1F
)

type compressedTransport struct {
	inner Transport
}

func newCompressedTransport(inner Transport) *compressedTransport {
	return &compressedTransport{inner: inner}
}

func (c *compressedTransport) Start() error       { return c.inner.Start() }
func (c *compressedTransport) Stop() error        { return c.inner.Stop() }
func (c *compressedTransport) IsConnected() bool { return c.inner.IsConnected() }
func (c *compressedTransport) Stats() TransportStats { return c.inner.Stats() }

func (c *compressedTransport) Send(data []byte) error {
	return c.inner.Send(compress(data))
}

func (c *compressedTransport) Receive(handler func(data []byte)) {
	c.inner.Receive(func(data []byte) {
		decompressed, err := decompress(data)
		if err != nil {
			handler(data)
			return
		}
		handler(decompressed)
	})
}

func compress(data []byte) []byte {
	if len(data) <= minCompressSize {
		out := make([]byte, 1, len(data)+1)
		out[0] = 0x00
		out = append(out, data...)
		return out
	}

	var buf bytes.Buffer
	buf.WriteByte(compressionMarker)
	w := lz4.NewWriter(&buf)
	_, _ = w.Write(data)
	_ = w.Close()

	if buf.Len() >= len(data)+1 {
		out := make([]byte, 1, len(data)+1)
		out[0] = 0x00
		out = append(out, data...)
		return out
	}

	return buf.Bytes()
}

func decompress(data []byte) ([]byte, error) {
	if len(data) < 1 {
		return data, nil
	}
	if data[0] == 0x00 {
		return data[1:], nil
	}
	r := lz4.NewReader(bytes.NewReader(data[1:]))
	return io.ReadAll(r)
}
