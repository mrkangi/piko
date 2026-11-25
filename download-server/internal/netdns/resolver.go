package netdns

import (
	"context"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"time"

	"bufio"
)

var fallbackServers = []string{"1.1.1.1:53", "8.8.8.8:53", "223.5.5.5:53", "119.29.29.29:53"}

// Configure installs a Termux-compatible resolver before any HTTP client is
// created. Android's libc resolver uses netd, but static Go binaries built
// without cgo fall back to 127.0.0.1/::1:53 when /etc/resolv.conf is absent.
func Configure() {
	if runtime.GOOS != "android" && strings.TrimSpace(os.Getenv("TERMUX_VERSION")) == "" {
		return
	}
	servers := discoverServers()
	if len(servers) == 0 {
		servers = append([]string(nil), fallbackServers...)
	}
	net.DefaultResolver = newResolver(servers)
}

func newResolver(servers []string) *net.Resolver {
	var next atomic.Uint64
	dialer := &net.Dialer{Timeout: 4 * time.Second}
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var lastErr error
			for range servers {
				index := next.Add(1) - 1
				server := servers[int(index%uint64(len(servers)))]
				conn, err := dialer.DialContext(ctx, network, server)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func discoverServers() []string {
	var paths []string
	if prefix := strings.TrimSpace(os.Getenv("PREFIX")); prefix != "" {
		paths = append(paths, filepath.Join(prefix, "etc", "resolv.conf"))
	}
	paths = append(paths,
		"/etc/resolv.conf",
		"/data/data/com.termux/files/usr/etc/resolv.conf",
	)
	seenPaths := make(map[string]struct{}, len(paths))
	for _, candidate := range paths {
		if _, seen := seenPaths[candidate]; seen {
			continue
		}
		seenPaths[candidate] = struct{}{}
		raw, err := os.ReadFile(candidate)
		if err != nil {
			continue
		}
		if servers := parseResolvConf(string(raw)); len(servers) > 0 {
			return servers
		}
	}
	return nil
}

func parseResolvConf(raw string) []string {
	var servers []string
	seen := make(map[string]struct{})
	scanner := bufio.NewScanner(strings.NewReader(raw))
	for scanner.Scan() {
		line := strings.TrimSpace(strings.SplitN(scanner.Text(), "#", 2)[0])
		fields := strings.Fields(line)
		if len(fields) < 2 || fields[0] != "nameserver" {
			continue
		}
		addr, err := netip.ParseAddr(fields[1])
		if err != nil || addr.IsLoopback() || addr.IsUnspecified() {
			continue
		}
		server := net.JoinHostPort(addr.String(), "53")
		if _, exists := seen[server]; exists {
			continue
		}
		seen[server] = struct{}{}
		servers = append(servers, server)
	}
	return servers
}
