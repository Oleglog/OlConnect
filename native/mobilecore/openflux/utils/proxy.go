package utils

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/net/proxy"
)

var (
	signalingProxyURL    *url.URL
	signalingProxyDialer proxy.Dialer
)

// SetSignalingProxy explicitly sets a SOCKS5/HTTP proxy for transport signaling (HTTP & WebSocket).
func SetSignalingProxy(proxyAddr string) error {
	proxyAddr = strings.TrimSpace(proxyAddr)
	if proxyAddr == "" {
		signalingProxyURL = nil
		signalingProxyDialer = nil
		return nil
	}
	d, err := CreateSocks5Dialer(proxyAddr, 10*time.Second)
	if err != nil {
		return fmt.Errorf("create signaling proxy dialer: %w", err)
	}
	if !strings.Contains(proxyAddr, "://") {
		proxyAddr = "socks5://" + proxyAddr
	}
	u, _ := url.Parse(proxyAddr)
	signalingProxyURL = u
	signalingProxyDialer = d
	log.Printf("[PROXY] Signaling proxy strictly enforced: %s", u.Redacted())
	return nil
}

// HasSignalingProxy reports whether an explicit signaling proxy is configured.
func HasSignalingProxy() bool {
	return signalingProxyDialer != nil
}

// GetSignalingProxyDialer returns the proxy dialer if configured.
func GetSignalingProxyDialer() proxy.Dialer {
	return signalingProxyDialer
}

// GetSignalingHTTPTransport returns an http.Transport strictly using the signaling proxy if set,
// or reading from the environment.
func GetSignalingHTTPTransport() *http.Transport {
	if signalingProxyDialer != nil {
		return &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return signalingProxyDialer.Dial(network, addr)
			},
			TLSHandshakeTimeout: 15 * time.Second,
			ForceAttemptHTTP2:   true,
		}
	}
	return &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   15 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		TLSHandshakeTimeout: 15 * time.Second,
		ForceAttemptHTTP2:   true,
	}
}

// ConfigureWebsocketDialerProxy configures proxy settings on a gorilla websocket.Dialer.
// If an explicit signaling proxy was set via SetSignalingProxy, it strictly enforces it.
// Otherwise it checks environment variables for targetURL.
func ConfigureWebsocketDialerProxy(dialer *websocket.Dialer, targetURL string) {
	if dialer == nil {
		return
	}
	if signalingProxyDialer != nil {
		dialer.NetDialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return signalingProxyDialer.Dial(network, addr)
		}
		dialer.Proxy = func(r *http.Request) (*url.URL, error) {
			return nil, nil // Strictly handled by NetDialContext
		}
		log.Printf("[PROXY] WebSocket strictly using signaling proxy: %s", signalingProxyURL.Redacted())
		return
	}

	// Fallback to environment
	httpURL := targetURL
	if strings.HasPrefix(httpURL, "wss://") {
		httpURL = "https://" + strings.TrimPrefix(httpURL, "wss://")
	} else if strings.HasPrefix(httpURL, "ws://") {
		httpURL = "http://" + strings.TrimPrefix(httpURL, "ws://")
	}

	req, err := http.NewRequest("GET", httpURL, nil)
	if err != nil {
		return
	}
	proxyURL, err := http.ProxyFromEnvironment(req)
	if err != nil || proxyURL == nil {
		return
	}

	switch strings.ToLower(proxyURL.Scheme) {
	case "socks5", "socks5h":
		forward := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
		socksDialer, err := proxy.FromURL(proxyURL, forward)
		if err != nil {
			log.Printf("[PROXY] Failed to create SOCKS5 dialer for %s: %v", proxyURL.Redacted(), err)
			return
		}
		dialer.NetDialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return socksDialer.Dial(network, addr)
		}
		dialer.Proxy = func(r *http.Request) (*url.URL, error) {
			return nil, nil // Handled by NetDialContext
		}
		log.Printf("[PROXY] WebSocket configured via SOCKS5 proxy: %s", proxyURL.Redacted())
	case "http", "https":
		dialer.Proxy = http.ProxyURL(proxyURL)
		log.Printf("[PROXY] WebSocket configured via HTTP proxy: %s", proxyURL.Redacted())
	}
}

// CreateSocks5Dialer creates a proxy.Dialer from a given proxy address string.
func CreateSocks5Dialer(proxyAddr string, timeout time.Duration) (proxy.Dialer, error) {
	proxyAddr = strings.TrimSpace(proxyAddr)
	if proxyAddr == "" {
		return nil, fmt.Errorf("empty proxy address")
	}
	if !strings.Contains(proxyAddr, "://") {
		proxyAddr = "socks5://" + proxyAddr
	}
	u, err := url.Parse(proxyAddr)
	if err != nil {
		return nil, fmt.Errorf("invalid proxy URL %q: %w", proxyAddr, err)
	}
	forward := &net.Dialer{Timeout: timeout, KeepAlive: 30 * time.Second}
	return proxy.FromURL(u, forward)
}
