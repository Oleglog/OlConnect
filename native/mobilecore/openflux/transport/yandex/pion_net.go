package yandex

import (
	"context"
	"net"
	"strings"
	"time"

	"github.com/pion/transport/v4"
	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/webrtc/v4"
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/netbind"
)

// newTelemostSettingEngine configures Pion's SettingEngine so all WebRTC
// sockets use netbind (Android VpnService.protect and/or physical interface
// binding) and Android 11+ devices where netlink interface enumeration is
// blocked by SELinux still discover their outbound IPv4 address.
func newTelemostSettingEngine() webrtc.SettingEngine {
	var se webrtc.SettingEngine
	se.SetNet(newBoundPionNet())
	se.SetInterfaceFilter(func(name string) bool {
		return !isTunnelInterfaceName(name)
	})
	return se
}

func isTunnelInterfaceName(name string) bool {
	lower := strings.ToLower(strings.TrimSpace(name))
	return strings.HasPrefix(lower, "tun") ||
		strings.HasPrefix(lower, "tap") ||
		strings.HasPrefix(lower, "ppp") ||
		strings.HasPrefix(lower, "wg") ||
		strings.HasPrefix(lower, "utun")
}

type boundPionNet struct {
	*stdnet.Net
	fallback []*transport.Interface
}

func newBoundPionNet() *boundPionNet {
	base, err := stdnet.NewNet()
	if err != nil || base == nil {
		base = &stdnet.Net{}
	}
	pn := &boundPionNet{Net: base}
	pn.ensureUsableInterfaces()
	return pn
}

func (n *boundPionNet) ensureUsableInterfaces() {
	ifaces, _ := n.Net.Interfaces()
	for _, ifc := range ifaces {
		if ifc == nil || isTunnelInterfaceName(ifc.Name) {
			continue
		}
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok && ipnet.IP != nil && ipnet.IP.To4() != nil && !ipnet.IP.IsLoopback() {
				return
			}
		}
	}
	if ip4 := netbind.DiscoverLocalIPv4(); ip4 != nil {
		ifc := transport.NewInterface(net.Interface{
			Index: 1,
			MTU:   1500,
			Name:  "wlan0",
			Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast | net.FlagRunning,
		})
		ifc.AddAddress(&net.IPNet{
			IP:   ip4,
			Mask: net.CIDRMask(24, 32),
		})
		n.fallback = []*transport.Interface{ifc}
	}
}

func (n *boundPionNet) Interfaces() ([]*transport.Interface, error) {
	ifaces, err := n.Net.Interfaces()
	if err == nil && len(ifaces) > 0 {
		out := make([]*transport.Interface, 0, len(ifaces))
		for _, ifc := range ifaces {
			if ifc != nil && !isTunnelInterfaceName(ifc.Name) {
				out = append(out, ifc)
			}
		}
		if len(out) > 0 && len(n.fallback) == 0 {
			return out, nil
		}
		if len(n.fallback) > 0 {
			return append(out, n.fallback...), nil
		}
	}
	if len(n.fallback) > 0 {
		return n.fallback, nil
	}
	n.ensureUsableInterfaces()
	return n.fallback, nil
}

func (n *boundPionNet) InterfaceByIndex(index int) (*transport.Interface, error) {
	if ifc, err := n.Net.InterfaceByIndex(index); err == nil && ifc != nil {
		return ifc, nil
	}
	for _, ifc := range n.fallback {
		if ifc.Index == index {
			return ifc, nil
		}
	}
	return n.Net.InterfaceByIndex(index)
}

func (n *boundPionNet) InterfaceByName(name string) (*transport.Interface, error) {
	if ifc, err := n.Net.InterfaceByName(name); err == nil && ifc != nil {
		return ifc, nil
	}
	for _, ifc := range n.fallback {
		if ifc.Name == name {
			return ifc, nil
		}
	}
	return n.Net.InterfaceByName(name)
}

func (n *boundPionNet) ListenPacket(network, address string) (net.PacketConn, error) {
	return netbind.ListenPacket(context.Background(), network, address)
}

func (n *boundPionNet) ListenUDP(network string, locAddr *net.UDPAddr) (transport.UDPConn, error) {
	addr := ":0"
	if locAddr != nil {
		addr = locAddr.String()
	}
	pc, err := netbind.ListenPacket(context.Background(), network, addr)
	if err != nil {
		return nil, err
	}
	if uc, ok := pc.(*net.UDPConn); ok {
		return uc, nil
	}
	_ = pc.Close()
	return net.ListenUDP(network, locAddr)
}

func (n *boundPionNet) Dial(network, address string) (net.Conn, error) {
	return netbind.Dialer(30 * time.Second).Dial(network, address)
}

func (n *boundPionNet) DialUDP(network string, laddr, raddr *net.UDPAddr) (transport.UDPConn, error) {
	d := netbind.Wrap(&net.Dialer{Timeout: 30 * time.Second, LocalAddr: laddr})
	c, err := d.Dial(network, raddr.String())
	if err != nil {
		return nil, err
	}
	if uc, ok := c.(*net.UDPConn); ok {
		return uc, nil
	}
	_ = c.Close()
	return net.DialUDP(network, laddr, raddr)
}

func (n *boundPionNet) DialTCP(network string, laddr, raddr *net.TCPAddr) (transport.TCPConn, error) {
	d := netbind.Wrap(&net.Dialer{Timeout: 30 * time.Second, LocalAddr: laddr})
	c, err := d.Dial(network, raddr.String())
	if err != nil {
		return nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		return tc, nil
	}
	_ = c.Close()
	return net.DialTCP(network, laddr, raddr)
}

func (n *boundPionNet) CreateDialer(d *net.Dialer) transport.Dialer {
	if d == nil {
		d = &net.Dialer{Timeout: 30 * time.Second}
	}
	return netbind.Wrap(d)
}

func (n *boundPionNet) CreateListenConfig(lc *net.ListenConfig) transport.ListenConfig {
	return netbind.WrapListenConfig(lc)
}
