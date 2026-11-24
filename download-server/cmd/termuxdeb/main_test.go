package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestSanitizeDebVersion(t *testing.T) {
	cases := map[string]string{
		"download-server":               "0.1.0",
		"download-server-1.2.3":         "1.2.3",
		"download-server-9.9.9-2026-vr": "9.9.9-2026-vr",
		"v2.0.1":                        "2.0.1",
		"test-build":                    "0.1.0~test-build",
	}
	for input, want := range cases {
		if got := SanitizeDebVersion(input); got != want {
			t.Fatalf("SanitizeDebVersion(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestBuildTermuxDeb(t *testing.T) {
	tmp := t.TempDir()
	binPath := filepath.Join(tmp, "download-server-android-arm64")
	if err := os.WriteFile(binPath, []byte("#!/bin/sh\necho ok\n"), 0o755); err != nil {
		t.Fatalf("write fake binary: %v", err)
	}

	debPath := filepath.Join(tmp, "download-server-termux-aarch64.deb")
	if err := BuildTermuxDeb(binPath, debPath, "piko-download-server", "download-server-1.2.3-test", "aarch64"); err != nil {
		t.Fatalf("BuildTermuxDeb: %v", err)
	}

	info, err := os.Stat(debPath)
	if err != nil || info.Size() == 0 {
		t.Fatalf("stat deb: %v", err)
	}

	if dpkgDeb, err := exec.LookPath("dpkg-deb"); err == nil {
		metaOut, err := exec.Command(dpkgDeb, "-I", debPath).CombinedOutput()
		if err != nil {
			t.Fatalf("dpkg-deb -I failed: %v\n%s", err, string(metaOut))
		}
		meta := string(metaOut)
		for _, expected := range []string{
			"Package: piko-download-server",
			"Version: 1.2.3-test",
			"Architecture: aarch64",
			"Depends: termux-services",
		} {
			if !strings.Contains(meta, expected) {
				t.Fatalf("dpkg-deb -I missing %q in:\n%s", expected, meta)
			}
		}

		contentsOut, err := exec.Command(dpkgDeb, "-c", debPath).CombinedOutput()
		if err != nil {
			t.Fatalf("dpkg-deb -c failed: %v\n%s", err, string(contentsOut))
		}
		contents := string(contentsOut)
		for _, pathItem := range []string{
			"./data/data/com.termux/files/usr/bin/download-server",
			"./data/data/com.termux/files/usr/etc/default/download-server",
			"./data/data/com.termux/files/usr/var/service/download-server/run",
			"./data/data/com.termux/files/usr/var/service/download-server/log/run",
		} {
			if !strings.Contains(contents, pathItem) {
				t.Fatalf("dpkg-deb -c missing %q in:\n%s", pathItem, contents)
			}
		}
	}
}
