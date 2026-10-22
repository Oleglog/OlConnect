// Package netbind keeps the core's own connections off its own tunnel.
//
// With the Windows full tunnel (--inbound=tun) the default route points at
// the OpenFlux adapter, but the carriers (Yandex, the node's direct port)
// must still leave through the real network, or they would loop into the
// tunnel they carry. Android gets this by excluding the app from its VPN;
// here every outbound socket of the core is bound to the physical
// interface instead (IP_UNICAST_IF), which holds whatever the routes say.
// Dial sites use Dialer or DialContext; Bind also covers the defaults
// (http.DefaultTransport, websocket.DefaultDialer, net.DefaultResolver).
package netbind

import (
	"context"
	"net"
	"net/http"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

type controlFunc = func(network, address string, c syscall.RawConn) error

var (
	currentBind    atomic.Pointer[controlFunc]
	currentProtect atomic.Pointer[func(fd int) bool]
)

// control applies the current interface binding and/or Android VpnService
// socket protector, if any.
func control(network, address string, c syscall.RawConn) error {
	if p := currentProtect.Load(); p != nil && *p != nil {
		protectFn := *p
		if err := c.Control(func(fd uintptr) {
			_ = protectFn(int(fd))
		}); err != nil {
			return err
		}
	}
	if f := currentBind.Load(); f != nil && *f != nil {
		return (*f)(network, address, c)
	}
	return nil
}

// SetProtector registers an Android VpnService.protect(fd) callback and hooks
// default HTTP and WebSocket dialers so all outbound OpenFlux sockets bypass
// the VPN tunnel. Note: we intentionally do NOT replace net.DefaultResolver
// with PreferGo: true on Android because Android has no /etc/resolv.conf and
// relies on the native cgo/bionic getaddrinfo resolver.
func SetProtector(protect func(fd int) bool) {
	if protect == nil {
		currentProtect.Store(nil)
		return
	}
	currentProtect.Store(&protect)
	installTransportDefaults()
}

// HasProtector reports whether an Android socket protector is registered.
func HasProtector() bool {
	p := currentProtect.Load()
	return p != nil && *p != nil
}

// Dialer is a net.Dialer that follows the binding and socket protector.
func Dialer(timeout time.Duration) *net.Dialer {
	return Wrap(&net.Dialer{Timeout: timeout, KeepAlive: 30 * time.Second})
}

// Wrap makes d follow the binding (keeping its own Control, if any).
func Wrap(d *net.Dialer) *net.Dialer {
	own := d.Control
	d.Control = func(network, address string, c syscall.RawConn) error {
		if own != nil {
			if err := own(network, address, c); err != nil {
				return err
			}
		}
		return control(network, address, c)
	}
	return d
}

// DialContext dials with a default Dialer (30 s timeout).
func DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return Dialer(30*time.Second).DialContext(ctx, network, address)
}

// WrapListenConfig makes lc follow the binding and socket protector (keeping its own Control, if any).
func WrapListenConfig(lc *net.ListenConfig) *net.ListenConfig {
	if lc == nil {
		lc = &net.ListenConfig{}
	}
	own := lc.Control
	lc.Control = func(network, address string, c syscall.RawConn) error {
		if own != nil {
			if err := own(network, address, c); err != nil {
				return err
			}
		}
		return control(network, address, c)
	}
	return lc
}

// ListenPacket opens a UDP/packet socket with the current binding and
// VpnService protector applied before bind.
func ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	lc := net.ListenConfig{Control: control}
	return lc.ListenPacket(ctx, network, address)
}

// DiscoverLocalIPv4 performs a kernel UDP route lookup using a protected socket
// to determine the primary non-tunnel outbound IPv4 address. Useful on Android 11+
// where netlink interface enumeration is blocked by SELinux.
func DiscoverLocalIPv4() net.IP {
	for _, target := range []string{"77.88.8.8:80", "1.1.1.1:80", "8.8.8.8:80"} {
		conn, err := Dialer(2*time.Second).Dial("udp4", target)
		if err != nil {
			continue
		}
		laddr, _ := conn.LocalAddr().(*net.UDPAddr)
		_ = conn.Close()
		if laddr != nil && laddr.IP != nil && !laddr.IP.IsLoopback() && !laddr.IP.IsUnspecified() {
			if ip4 := laddr.IP.To4(); ip4 != nil {
				return ip4
			}
		}
	}
	return nil
}

// Bound reports whether sockets are bound to an interface or protected now.
func Bound() bool {
	return currentBind.Load() != nil || currentProtect.Load() != nil
}

func installTransportDefaults() {
	if t, ok := http.DefaultTransport.(*http.Transport); ok {
		t.DialContext = DialContext
	}
	websocket.DefaultDialer.NetDialContext = DialContext
}

func installDefaults() {
	installTransportDefaults()
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return Dialer(10*time.Second).DialContext(ctx, network, address)
		},
	}
}

// install sets the binding and points the standard library's defaults at
// bound dialers, so code that never names a dialer is covered too.
func install(f controlFunc) {
	currentBind.Store(&f)
	installDefaults()
}

