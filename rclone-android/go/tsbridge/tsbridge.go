// Package tsbridge provides a minimal Go bridge over Tailscale's tsnet
// library, exposed via gomobile for Haven's per-app Tailscale support
// (#102 follow-up).
//
// Shares the public API shape with [wgbridge] — StartTunnel →
// TunnelHandle → Dial → Conn → Read/Write/Close — so the Kotlin side
// can treat both backends uniformly through [sh.haven.core.tunnel.Tunnel].
//
// Differences from wgbridge worth noting:
//   - Tailscale needs a writable state directory (control state, node
//     keys, cert cache). The caller passes an absolute path owned by the
//     app — typically context.filesDir/tailscale-<configId>/ — which
//     tsnet creates if missing.
//   - Auth is an authkey (tskey-auth-...) rather than a wg-quick config.
//     The authkey is used once to join the tailnet; subsequent starts
//     reuse the persisted state directly.
//   - MagicDNS works transparently because tsnet.Server.Dial routes
//     hostname lookups through the tailnet resolver. A dial to
//     "my-laptop.tailnet.ts.net:22" resolves + tunnels in one call.
package tsbridge

/*
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/types/netmap"
)

const nodeAliasCapability = "sh.larktun.alias"

// init wires an Android-safe interface enumerator into tsnet's
// RegisterInterfaceGetter hook. Tailscale added this hook specifically
// for Android (SDK 30+) because Go stdlib's `net.Interfaces()` opens
// NETLINK_ROUTE sockets which `untrusted_app` SELinux contexts can't.
// Without this hook, tsnet.Up() bails with
// "netlinkrib: permission denied" before it can do anything else.
//
// Our implementation uses libc's `getifaddrs(3)` via cgo. On Android
// bionic (API 24+) this is implemented on top of `ioctl(SIOCGIFCONF)`
// on a UDP socket — a syscall path that `untrusted_app` IS permitted
// to use (the same path Java's `NetworkInterface.getNetworkInterfaces`
// uses). It gives us names + flags + IPv4/IPv6 addresses without ever
// touching netlink or `/proc/net`, both of which Android's SELinux
// policy blocks.
func init() {
	netmon.RegisterInterfaceGetter(androidSafeInterfaces)
}

func androidSafeInterfaces() ([]netmon.Interface, error) {
	// Try Go stdlib first — works on desktop Linux and older Androids
	// where netlink was still allowed. Falls through on EACCES.
	if ifs, err := net.Interfaces(); err == nil && len(ifs) > 0 {
		out := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			out[i].Interface = &ifs[i]
			// Addrs() also uses netlink under the hood; fetch via
			// getifaddrs instead and stash them where tsnet looks.
			out[i].AltAddrs, _ = getifaddrsAddrs(ifs[i].Name)
		}
		return out, nil
	}
	return getifaddrsInterfaces()
}

// ifaceInfo is an intermediate struct we assemble from getifaddrs
// before converting to netmon.Interface. Grouped by interface name —
// getifaddrs returns one entry per (interface, address) pair and we
// need one netmon.Interface per interface.
type ifaceInfo struct {
	name  string
	index int
	flags net.Flags
	mtu   int
	addrs []net.Addr
}

// getifaddrsInterfaces enumerates network interfaces via libc's
// getifaddrs(3). SELinux-safe on Android untrusted_app since the
// underlying syscalls are ioctl(SIOCGIFCONF) + socket operations, not
// netlink.
func getifaddrsInterfaces() ([]netmon.Interface, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)

	byName := map[string]*ifaceInfo{}
	for ifa := head; ifa != nil; ifa = ifa.ifa_next {
		name := C.GoString(ifa.ifa_name)
		info := byName[name]
		if info == nil {
			info = &ifaceInfo{
				name:  name,
				flags: translateFlags(uint32(ifa.ifa_flags)),
			}
			byName[name] = info
		}
		if addr := sockaddrToAddr(ifa.ifa_addr, ifa.ifa_netmask); addr != nil {
			info.addrs = append(info.addrs, addr)
		}
	}

	out := make([]netmon.Interface, 0, len(byName))
	for _, info := range byName {
		ni := &net.Interface{
			Index: info.index,
			Name:  info.name,
			Flags: info.flags,
			MTU:   info.mtu,
		}
		out = append(out, netmon.Interface{
			Interface: ni,
			AltAddrs:  info.addrs,
		})
	}
	return out, nil
}

// getifaddrsAddrs returns just the addresses for a named interface, for
// the "Go stdlib net.Interfaces worked but Addrs() may not" code path.
func getifaddrsAddrs(name string) ([]net.Addr, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)
	var addrs []net.Addr
	for ifa := head; ifa != nil; ifa = ifa.ifa_next {
		if C.GoString(ifa.ifa_name) != name {
			continue
		}
		if a := sockaddrToAddr(ifa.ifa_addr, ifa.ifa_netmask); a != nil {
			addrs = append(addrs, a)
		}
	}
	return addrs, nil
}

// translateFlags maps Linux IFF_* flags to Go's net.Flags.
func translateFlags(f uint32) net.Flags {
	var out net.Flags
	if f&C.IFF_UP != 0 {
		out |= net.FlagUp
	}
	if f&C.IFF_BROADCAST != 0 {
		out |= net.FlagBroadcast
	}
	if f&C.IFF_LOOPBACK != 0 {
		out |= net.FlagLoopback
	}
	if f&C.IFF_POINTOPOINT != 0 {
		out |= net.FlagPointToPoint
	}
	if f&C.IFF_MULTICAST != 0 {
		out |= net.FlagMulticast
	}
	if f&C.IFF_RUNNING != 0 {
		out |= net.FlagRunning
	}
	return out
}

// sockaddrToAddr converts a C sockaddr (IPv4 or IPv6) to a net.IPNet
// carrying the address + prefix length. Returns nil for families we
// don't care about (AF_PACKET for MAC addresses, etc).
func sockaddrToAddr(sa, nm *C.struct_sockaddr) net.Addr {
	if sa == nil {
		return nil
	}
	switch sa.sa_family {
	case C.AF_INET:
		sin := (*C.struct_sockaddr_in)(unsafe.Pointer(sa))
		addr := (*[4]byte)(unsafe.Pointer(&sin.sin_addr))[:]
		ip := netip.AddrFrom4(*(*[4]byte)(addr))
		prefix := 32
		if nm != nil && nm.sa_family == C.AF_INET {
			mask := (*C.struct_sockaddr_in)(unsafe.Pointer(nm))
			maskBytes := (*[4]byte)(unsafe.Pointer(&mask.sin_addr))[:]
			prefix = countLeadingOnes(maskBytes)
		}
		return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(prefix, 32)}
	case C.AF_INET6:
		sin := (*C.struct_sockaddr_in6)(unsafe.Pointer(sa))
		addrBytes := (*[16]byte)(unsafe.Pointer(&sin.sin6_addr))[:]
		ip := netip.AddrFrom16(*(*[16]byte)(addrBytes))
		prefix := 128
		if nm != nil && nm.sa_family == C.AF_INET6 {
			mask := (*C.struct_sockaddr_in6)(unsafe.Pointer(nm))
			maskBytes := (*[16]byte)(unsafe.Pointer(&mask.sin6_addr))[:]
			prefix = countLeadingOnes(maskBytes)
		}
		return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(prefix, 128)}
	}
	return nil
}

// countLeadingOnes returns the prefix length of a netmask byte slice.
func countLeadingOnes(mask []byte) int {
	ones := 0
	for _, b := range mask {
		if b == 0xff {
			ones += 8
			continue
		}
		for b&0x80 != 0 {
			ones++
			b <<= 1
		}
		break
	}
	return ones
}

// TunnelHandle wraps a live tsnet.Server. Safe to Dial concurrently;
// Close is idempotent but not safe to race with Dial.
type TunnelHandle struct {
	srv    *tsnet.Server
	mu     sync.Mutex
	closed bool
}

// Conn is a TCP connection through the tunnel. Mirrors wgbridge.Conn so
// the Kotlin adapter can treat both the same way.
type Conn struct {
	c net.Conn
}

type statusPayload struct {
	Status          string        `json:"status"`
	BackendState    string        `json:"backendState,omitempty"`
	Hostname        string        `json:"hostname,omitempty"`
	SelfDisplayName string        `json:"selfDisplayName,omitempty"`
	SelfAliasName   string        `json:"selfAliasName,omitempty"`
	TailscaleIPs    []string      `json:"tailscaleIPs"`
	SelfDNSName     string        `json:"selfDNSName,omitempty"`
	TailnetName     string        `json:"tailnetName,omitempty"`
	MagicDNSSuffix  string        `json:"magicDNSSuffix,omitempty"`
	MagicDNSEnabled bool          `json:"magicDNSEnabled,omitempty"`
	PeerCount       int           `json:"peerCount"`
	Peers           []peerPayload `json:"peers"`
	LastError       string        `json:"lastError,omitempty"`
	UpdatedAt       string        `json:"updatedAt"`
}

type peerPayload struct {
	ID             string   `json:"id"`
	PublicKey      string   `json:"publicKey,omitempty"`
	Name           string   `json:"name,omitempty"`
	ComputedName   string   `json:"computedName,omitempty"`
	DisplayName    string   `json:"displayName,omitempty"`
	AliasName      string   `json:"aliasName,omitempty"`
	HostName       string   `json:"hostName,omitempty"`
	DNSName        string   `json:"dnsName,omitempty"`
	OS             string   `json:"os,omitempty"`
	TailscaleIPs   []string `json:"tailscaleIPs"`
	Online         bool     `json:"online"`
	Active         bool     `json:"active"`
	ExitNode       bool     `json:"exitNode"`
	ExitNodeOption bool     `json:"exitNodeOption"`
	CurAddr        string   `json:"curAddr,omitempty"`
	LastSeen       string   `json:"lastSeen,omitempty"`
	LastHandshake  string   `json:"lastHandshake,omitempty"`
	Relay          string   `json:"relay,omitempty"`
	PeerRelay      string   `json:"peerRelay,omitempty"`
	KeyExpiry      string   `json:"keyExpiry,omitempty"`
}

type pingPayload struct {
	IP             string  `json:"ip,omitempty"`
	NodeIP         string  `json:"nodeIP,omitempty"`
	NodeName       string  `json:"nodeName,omitempty"`
	Error          string  `json:"error,omitempty"`
	LatencySeconds float64 `json:"latencySeconds,omitempty"`
	Endpoint       string  `json:"endpoint,omitempty"`
	PeerRelay      string  `json:"peerRelay,omitempty"`
	DERPRegionID   int     `json:"derpRegionID,omitempty"`
	DERPRegionCode string  `json:"derpRegionCode,omitempty"`
	PeerAPIPort    uint16  `json:"peerAPIPort,omitempty"`
	PeerAPIURL     string  `json:"peerAPIURL,omitempty"`
	IsLocalIP      bool    `json:"isLocalIP,omitempty"`
}

// StartTunnel brings up a tailnet using the given authkey and state
// directory. hostname is advertised to the tailnet (shows up in the
// admin console); blank picks tsnet's default.
//
// controlURL points the client at a coordination server other than
// Tailscale's hosted controlplane.tailscale.com. Empty string keeps
// the default; non-empty is typically a self-hosted Headscale server
// (e.g. "https://headscale.example.com"). Tailscale's own control
// plane and Headscale share the same control protocol, so the same
// tsnet client speaks both — see #124, mcbalaam (Headscale users).
//
// Blocks until authenticated AND the peer map has been received, so a
// subsequent Dial to a MagicDNS name works first-try. Internal timeout
// is 60 s — first-run authkey consumption + control-plane handshake +
// peer-map sync can add up on a phone with flaky NAT. Returns errors
// carrying the underlying Tailscale diagnostic; common causes include
// expired/bad authkey, tagged-node ACL restrictions, coordination
// server unreachable.
//
// tsnet's internal logs route through Go's default logger which
// gomobile surfaces under logcat's "GoLog" tag, so a stuck handshake
// or DERP failure is diagnosable without a separate debug build.
func StartTunnel(authKey, stateDir, hostname, controlURL string) (*TunnelHandle, error) {
	if authKey == "" {
		return nil, errors.New("authkey required")
	}
	if stateDir == "" {
		return nil, errors.New("state directory required")
	}
	if hostname == "" {
		hostname = "haven-android"
	}
	srv := &tsnet.Server{
		AuthKey:    authKey,
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: controlURL, // empty = default controlplane.tailscale.com
		Ephemeral:  false,
		Logf:       tsnetLogf,
		UserLogf:   tsnetLogf,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if _, err := srv.Up(ctx); err != nil {
		_ = srv.Close()
		return nil, fmt.Errorf("tsnet.Up: %w", err)
	}
	// Up() returns when login is accepted, but the peer-map sync runs
	// slightly after. Wait for self.Online or at least one peer before
	// returning — otherwise the first Dial races against MagicDNS and
	// times out.
	if err := waitForPeerMap(ctx, srv); err != nil {
		_ = srv.Close()
		return nil, fmt.Errorf("tsnet wait-for-peers: %w", err)
	}
	return &TunnelHandle{srv: srv}, nil
}

// tsnetLogf pipes tsnet's internal log lines through Go's default
// logger, which gomobile routes to logcat as "GoLog".
func tsnetLogf(format string, args ...any) {
	fmt.Printf("[tsnet] "+format+"\n", args...)
}

// waitForPeerMap polls the local client until either the self node is
// marked online or at least one peer is present. A fresh tailnet join
// typically reports Self.Online first; a reconnect with cached peers
// may populate Peer first. Either signals that MagicDNS resolution
// should work on the next dial.
func waitForPeerMap(ctx context.Context, srv *tsnet.Server) error {
	lc, err := srv.LocalClient()
	if err != nil {
		return fmt.Errorf("LocalClient: %w", err)
	}
	var lastErr error
	for {
		st, err := lc.Status(ctx)
		if err == nil && st.Self != nil && (st.Self.Online || len(st.Peer) > 0) {
			return nil
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return fmt.Errorf("timeout (last status err=%v)", lastErr)
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// Dial opens a TCP connection through the tailnet. host may be a
// MagicDNS name (foo.tailnet.ts.net), a tailnet IP (100.x.y.z), or
// any IP that the tailnet can reach (e.g. a subnet-router hop).
// timeoutMs <= 0 means 30 s.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()

	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()
	c, err := srv.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d via tailnet: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// StatusJSON returns a compact tailnet status snapshot for Android UI code.
// It intentionally mirrors the iOS AppEngine payload shape so both clients
// can render Larktun device rows from the same fields.
func (t *TunnelHandle) StatusJSON() string {
	payload := t.statusPayload()
	data, err := json.Marshal(payload)
	if err != nil {
		return fmt.Sprintf(`{"status":"failed","lastError":%q}`, err.Error())
	}
	return string(data)
}

// PingJSON runs a TSMP ping through the app-only tsnet runtime and returns
// a compact JSON payload for the Android Larktun device menu.
func (t *TunnelHandle) PingJSON(ip string, timeoutMs int) (string, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return "", errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()
	if srv == nil {
		return "", errors.New("tunnel not started")
	}

	addr, err := netip.ParseAddr(strings.TrimSpace(ip))
	if err != nil {
		return "", fmt.Errorf("parse ping address %q: %w", ip, err)
	}
	if timeoutMs <= 0 {
		timeoutMs = 3_000
	}

	lc, err := srv.LocalClient()
	if err != nil {
		return "", fmt.Errorf("LocalClient: %w", err)
	}

	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()
	result, err := lc.Ping(ctx, addr, tailcfg.PingTSMP)
	if err != nil {
		return "", err
	}

	data, err := json.Marshal(pingPayloadFromResult(result))
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (t *TunnelHandle) statusPayload() statusPayload {
	payload := statusPayload{
		Status:       "idle",
		TailscaleIPs: []string{},
		Peers:        []peerPayload{},
		UpdatedAt:    time.Now().Format(time.RFC3339),
	}

	t.mu.Lock()
	closed := t.closed
	srv := t.srv
	t.mu.Unlock()
	if closed || srv == nil {
		payload.LastError = "tunnel closed"
		return payload
	}

	payload.Status = "running"
	lc, err := srv.LocalClient()
	if err != nil {
		payload.LastError = err.Error()
		return payload
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	status, err := lc.Status(ctx)
	if err != nil {
		payload.LastError = err.Error()
		return payload
	}
	applyIPNStatus(&payload, status)

	netMap, err := currentNetworkMap(ctx, lc)
	if err != nil {
		payload.LastError = err.Error()
		return payload
	}
	applyNetworkMap(&payload, netMap)
	return payload
}

func pingPayloadFromResult(result *ipnstate.PingResult) pingPayload {
	if result == nil {
		return pingPayload{Error: "empty ping result"}
	}
	return pingPayload{
		IP:             result.IP,
		NodeIP:         result.NodeIP,
		NodeName:       result.NodeName,
		Error:          result.Err,
		LatencySeconds: result.LatencySeconds,
		Endpoint:       result.Endpoint,
		PeerRelay:      result.PeerRelay,
		DERPRegionID:   result.DERPRegionID,
		DERPRegionCode: result.DERPRegionCode,
		PeerAPIPort:    result.PeerAPIPort,
		PeerAPIURL:     result.PeerAPIURL,
		IsLocalIP:      result.IsLocalIP,
	}
}

// Close tears down the tailnet connection. The state directory is kept
// intact so a subsequent StartTunnel picks up without re-auth.
func (t *TunnelHandle) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true
	if t.srv != nil {
		_ = t.srv.Close()
		t.srv = nil
	}
}

func applyIPNStatus(payload *statusPayload, status *ipnstate.Status) {
	if payload == nil || status == nil {
		return
	}

	payload.BackendState = status.BackendState
	payload.TailscaleIPs = payload.TailscaleIPs[:0]
	for _, ip := range status.TailscaleIPs {
		payload.TailscaleIPs = append(payload.TailscaleIPs, ip.String())
	}
	if status.Self != nil {
		payload.SelfDNSName = strings.TrimSuffix(status.Self.DNSName, ".")
	}
	if status.CurrentTailnet != nil {
		payload.TailnetName = status.CurrentTailnet.Name
		payload.MagicDNSSuffix = status.CurrentTailnet.MagicDNSSuffix
		payload.MagicDNSEnabled = status.CurrentTailnet.MagicDNSEnabled
	}
	payload.PeerCount = len(status.Peer)
	payload.Peers = payload.Peers[:0]
	for _, peerKey := range status.Peers() {
		peer := status.Peer[peerKey]
		if peer == nil {
			continue
		}
		payload.Peers = append(payload.Peers, peerPayloadFromStatus(peerKey.String(), peer))
	}
	payload.PeerCount = len(payload.Peers)
}

func currentNetworkMap(ctx context.Context, lc *local.Client) (*netmap.NetworkMap, error) {
	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialNetMap|ipn.NotifyNoPrivateKeys)
	if err != nil {
		return nil, err
	}
	defer watcher.Close()

	for {
		notify, err := watcher.Next()
		if err != nil {
			return nil, err
		}
		if notify.NetMap != nil {
			return notify.NetMap, nil
		}
	}
}

func applyNetworkMap(payload *statusPayload, nm *netmap.NetworkMap) {
	if payload == nil || nm == nil {
		return
	}

	if payload.TailnetName == "" {
		payload.TailnetName = nm.DomainName()
	}
	if payload.MagicDNSSuffix == "" {
		payload.MagicDNSSuffix = nm.MagicDNSSuffix()
	}
	if nm.SelfNode.Valid() {
		applySelfNode(payload, nm.SelfNode)
	}

	peerIndex := make(map[string]int, len(payload.Peers)*2)
	for index, peer := range payload.Peers {
		if peer.ID != "" {
			peerIndex[peer.ID] = index
		}
		if peer.PublicKey != "" {
			peerIndex[peer.PublicKey] = index
		}
	}

	for _, node := range nm.Peers {
		if !node.Valid() {
			continue
		}

		index, ok := peerIndex[string(node.StableID())]
		if !ok {
			index, ok = peerIndex[node.Key().String()]
		}
		if ok {
			applyNodeToPeerPayload(&payload.Peers[index], node)
			continue
		}

		peer := peerPayloadFromNode(node)
		payload.Peers = append(payload.Peers, peer)
		if peer.ID != "" {
			peerIndex[peer.ID] = len(payload.Peers) - 1
		}
		if peer.PublicKey != "" {
			peerIndex[peer.PublicKey] = len(payload.Peers) - 1
		}
	}
	payload.PeerCount = len(payload.Peers)
}

func applySelfNode(payload *statusPayload, node tailcfg.NodeView) {
	displayName := nodeDisplayName(node)
	if displayName != "" {
		payload.SelfDisplayName = displayName
	}
	if aliasName := nodeAliasName(node); aliasName != "" {
		payload.SelfAliasName = aliasName
	}
	if payload.SelfDNSName == "" {
		payload.SelfDNSName = nodeDNSName(node)
	}
	if len(payload.TailscaleIPs) == 0 {
		payload.TailscaleIPs = nodeTailscaleIPs(node)
	}
}

func peerPayloadFromStatus(publicKey string, peer *ipnstate.PeerStatus) peerPayload {
	payload := peerPayload{
		ID:             string(peer.ID),
		PublicKey:      publicKey,
		HostName:       peer.HostName,
		DNSName:        strings.TrimSuffix(peer.DNSName, "."),
		OS:             peer.OS,
		TailscaleIPs:   make([]string, 0, len(peer.TailscaleIPs)),
		Online:         peer.Online,
		Active:         peer.Active,
		ExitNode:       peer.ExitNode,
		ExitNodeOption: peer.ExitNodeOption,
		CurAddr:        peer.CurAddr,
		Relay:          peer.Relay,
		PeerRelay:      peer.PeerRelay,
		LastSeen:       formatTime(peer.LastSeen),
		LastHandshake:  formatTime(peer.LastHandshake),
	}
	if peer.KeyExpiry != nil {
		payload.KeyExpiry = formatTime(*peer.KeyExpiry)
	}
	if payload.ID == "" {
		payload.ID = publicKey
	}
	for _, ip := range peer.TailscaleIPs {
		payload.TailscaleIPs = append(payload.TailscaleIPs, ip.String())
	}
	return payload
}

func peerPayloadFromNode(node tailcfg.NodeView) peerPayload {
	displayName := nodeDisplayName(node)
	payload := peerPayload{
		ID:             string(node.StableID()),
		PublicKey:      node.Key().String(),
		Name:           nodeName(node),
		ComputedName:   nodeComputedName(node),
		DisplayName:    displayName,
		AliasName:      nodeAliasName(node),
		HostName:       nodeHostName(node),
		DNSName:        nodeDNSName(node),
		OS:             nodeOS(node),
		TailscaleIPs:   nodeTailscaleIPs(node),
		Online:         node.Online().GetOr(false),
		ExitNodeOption: nodeIsExitNode(node),
		LastSeen:       nodeLastSeen(node),
		KeyExpiry:      nodeKeyExpiry(node),
	}
	if payload.ID == "" {
		payload.ID = payload.PublicKey
	}
	return payload
}

func applyNodeToPeerPayload(peer *peerPayload, node tailcfg.NodeView) {
	if peer == nil || !node.Valid() {
		return
	}

	if peer.ID == "" {
		peer.ID = string(node.StableID())
	}
	if peer.PublicKey == "" {
		peer.PublicKey = node.Key().String()
	}
	peer.Name = firstNonEmptyString(peer.Name, nodeName(node))
	peer.ComputedName = firstNonEmptyString(peer.ComputedName, nodeComputedName(node))
	peer.DisplayName = firstNonEmptyString(peer.DisplayName, nodeDisplayName(node))
	peer.AliasName = firstNonEmptyString(peer.AliasName, nodeAliasName(node))
	peer.HostName = firstNonEmptyString(peer.HostName, nodeHostName(node))
	peer.DNSName = firstNonEmptyString(peer.DNSName, nodeDNSName(node))
	peer.OS = firstNonEmptyString(peer.OS, nodeOS(node))
	if len(peer.TailscaleIPs) == 0 {
		peer.TailscaleIPs = nodeTailscaleIPs(node)
	}
	if online, ok := node.Online().GetOk(); ok {
		peer.Online = online
	}
	if !peer.ExitNodeOption {
		peer.ExitNodeOption = nodeIsExitNode(node)
	}
	if peer.LastSeen == "" {
		peer.LastSeen = nodeLastSeen(node)
	}
	if peer.KeyExpiry == "" {
		peer.KeyExpiry = nodeKeyExpiry(node)
	}
}

func nodeName(node tailcfg.NodeView) string {
	if !node.Valid() {
		return ""
	}
	return strings.Trim(strings.TrimSpace(node.Name()), ".")
}

func nodeComputedName(node tailcfg.NodeView) string {
	if !node.Valid() {
		return ""
	}
	return strings.Trim(strings.TrimSpace(node.ComputedName()), ".")
}

func nodeAliasName(node tailcfg.NodeView) string {
	if !node.Valid() {
		return ""
	}

	values, ok := node.CapMap().GetOk(nodeAliasCapability)
	if !ok {
		return ""
	}

	for _, rawMessage := range values.All() {
		if alias := aliasFromRawMessage(rawMessage); alias != "" {
			return alias
		}
	}
	return ""
}

func nodeDisplayName(node tailcfg.NodeView) string {
	if !node.Valid() {
		return ""
	}
	if value := nodeAliasName(node); value != "" {
		return value
	}
	if value := nodeHostName(node); value != "" {
		return value
	}
	if value := nodeComputedName(node); value != "" {
		return value
	}
	if value := nodeName(node); value != "" {
		return value
	}
	return node.Key().String()
}

func aliasFromRawMessage(raw tailcfg.RawMessage) string {
	data := []byte(raw)
	if len(strings.TrimSpace(string(data))) == 0 {
		return ""
	}

	var value string
	if err := json.Unmarshal(data, &value); err == nil {
		return strings.TrimSpace(value)
	}

	var values []string
	if err := json.Unmarshal(data, &values); err == nil {
		for _, value := range values {
			if alias := strings.TrimSpace(value); alias != "" {
				return alias
			}
		}
	}

	fallback := strings.Trim(strings.TrimSpace(string(raw)), `"`)
	if fallback == "null" {
		return ""
	}
	return fallback
}

func nodeHostName(node tailcfg.NodeView) string {
	if !node.Valid() || !node.Hostinfo().Valid() {
		return ""
	}
	return strings.TrimSpace(node.Hostinfo().Hostname())
}

func nodeDNSName(node tailcfg.NodeView) string {
	return nodeName(node)
}

func nodeOS(node tailcfg.NodeView) string {
	if !node.Valid() || !node.Hostinfo().Valid() {
		return ""
	}
	return strings.TrimSpace(node.Hostinfo().OS())
}

func nodeTailscaleIPs(node tailcfg.NodeView) []string {
	if !node.Valid() {
		return []string{}
	}
	addresses := node.Addresses()
	ips := make([]string, 0, addresses.Len())
	for _, address := range addresses.All() {
		ips = append(ips, address.Addr().String())
	}
	return ips
}

func nodeIsExitNode(node tailcfg.NodeView) bool {
	if !node.Valid() {
		return false
	}
	var hasIPv4Default, hasIPv6Default bool
	for _, allowedIP := range node.AllowedIPs().All() {
		switch allowedIP.String() {
		case "0.0.0.0/0":
			hasIPv4Default = true
		case "::/0":
			hasIPv6Default = true
		}
	}
	return hasIPv4Default && hasIPv6Default
}

func nodeLastSeen(node tailcfg.NodeView) string {
	if !node.Valid() {
		return ""
	}
	value, ok := node.LastSeen().GetOk()
	if !ok {
		return ""
	}
	return formatTime(value)
}

func nodeKeyExpiry(node tailcfg.NodeView) string {
	if !node.Valid() {
		return ""
	}
	return formatTime(node.KeyExpiry())
}

func firstNonEmptyString(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func formatTime(value time.Time) string {
	if value.IsZero() {
		return ""
	}
	return value.Format(time.RFC3339)
}

// Read returns up to size bytes. Signals EOF the same way wgbridge does
// (nil slice + io.EOF) so the Kotlin InputStream wrapper can treat both
// backends identically.
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	if err == nil {
		err = io.EOF
	}
	return nil, err
}

// Write writes all of data. Gomobile copies []byte across JNI so the
// caller's array isn't mutated here.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}
