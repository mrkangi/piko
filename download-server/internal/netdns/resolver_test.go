package netdns

import (
	"context"
	"encoding/binary"
	"net"
	"reflect"
	"testing"
	"time"
)

func TestParseResolvConfSkipsBrokenLoopbackServers(t *testing.T) {
	raw := `
# Termux DNS
nameserver ::1
nameserver 127.0.0.1
nameserver 1.1.1.1
nameserver 1.1.1.1
nameserver 2001:4860:4860::8888
`
	got := parseResolvConf(raw)
	want := []string{"1.1.1.1:53", "[2001:4860:4860::8888]:53"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseResolvConf() = %v; want %v", got, want)
	}
}

func TestCustomResolverQueriesConfiguredServer(t *testing.T) {
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 1500)
		n, addr, readErr := conn.ReadFrom(buf)
		if readErr != nil || n < 17 {
			return
		}
		questionEnd := 12
		for questionEnd < n && buf[questionEnd] != 0 {
			questionEnd += int(buf[questionEnd]) + 1
		}
		questionEnd++
		if questionEnd+4 > n {
			return
		}
		questionEnd += 4

		response := make([]byte, 12+questionEnd-12+16)
		copy(response[:2], buf[:2])
		response[2] = 0x81
		response[3] = 0x80
		binary.BigEndian.PutUint16(response[4:6], 1)
		binary.BigEndian.PutUint16(response[6:8], 1)
		copy(response[12:], buf[12:questionEnd])
		answer := response[questionEnd:]
		answer[0] = 0xc0
		answer[1] = 0x0c
		binary.BigEndian.PutUint16(answer[2:4], 1)
		binary.BigEndian.PutUint16(answer[4:6], 1)
		binary.BigEndian.PutUint32(answer[6:10], 60)
		binary.BigEndian.PutUint16(answer[10:12], 4)
		copy(answer[12:16], net.IPv4(203, 0, 113, 7).To4())
		_, _ = conn.WriteTo(response, addr)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	resolver := newResolver([]string{conn.LocalAddr().String()})
	addrs, err := resolver.LookupIP(ctx, "ip4", "pikpak.test")
	if err != nil {
		t.Fatalf("LookupIP failed: %v", err)
	}
	if len(addrs) != 1 || addrs[0].String() != "203.0.113.7" {
		t.Fatalf("unexpected resolver result: %v", addrs)
	}
	<-done
}
