package netproxy

import "testing"

func TestNormalizeProxyURL(t *testing.T) {
	tests := []struct {
		name    string
		raw     string
		want    string
		wantErr bool
	}{
		{name: "empty", raw: "", want: ""},
		{name: "http", raw: " HTTP://127.0.0.1:8080 ", want: "http://127.0.0.1:8080"},
		{name: "https", raw: "https://proxy.example.com", want: "https://proxy.example.com"},
		{name: "socks5", raw: "socks5://user:pass@127.0.0.1:1080", want: "socks5://user:pass@127.0.0.1:1080"},
		{name: "socks5h", raw: "socks5h://127.0.0.1:1080", want: "socks5h://127.0.0.1:1080"},
		{name: "unsupported", raw: "ftp://127.0.0.1:21", wantErr: true},
		{name: "missing host", raw: "http://", wantErr: true},
		{name: "socks missing port", raw: "socks5://127.0.0.1", wantErr: true},
		{name: "path", raw: "http://127.0.0.1:8080/proxy", wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := Normalize(test.raw)
			if test.wantErr {
				if err == nil {
					t.Fatalf("expected error, got %q", got)
				}
				return
			}
			if err != nil || got != test.want {
				t.Fatalf("Normalize(%q) = %q, %v; want %q", test.raw, got, err, test.want)
			}
		})
	}
}

func TestMaskProxyCredentials(t *testing.T) {
	got, err := Mask("socks5://alice:secret@127.0.0.1:1080")
	if err != nil {
		t.Fatal(err)
	}
	if got != "socks5://alice:***@127.0.0.1:1080" {
		t.Fatalf("unexpected masked proxy: %s", got)
	}
}
