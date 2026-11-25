package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestSanitizeVersion(t *testing.T) {
	cases := map[string]string{
		"download-server":                    "0.1.0",
		"download-server-1.2.3":              "1.2.3",
		"download-server-9.9.9-2026-web-ui":  "9.9.9-2026-web-ui",
		"v2.0.1":                             "2.0.1",
		"test build":                         "0.1.0-test-build",
		"../../evil":                         "0.1.0-evil",
		"  9.9.9.20261125083934  ":           "9.9.9-20261125083934",
		"download-server-9.9.9+meta":         "9.9.9+meta",
		"download-server-9.9.9-20261125-x86": "9.9.9-20261125-x86",
	}
	for input, want := range cases {
		if got := SanitizeVersion(input); got != want {
			t.Fatalf("SanitizeVersion(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestValidatePlatform(t *testing.T) {
	for _, platform := range []string{"x86", "arm"} {
		if err := validatePlatform(platform); err != nil {
			t.Fatalf("validatePlatform(%q): %v", platform, err)
		}
	}
	if err := validatePlatform("armv7"); err == nil {
		t.Fatal("expected unsupported platform error")
	}
}

func TestBuildFPK(t *testing.T) {
	tmp := t.TempDir()
	binPath := filepath.Join(tmp, "download-server-linux-amd64")
	if err := os.WriteFile(binPath, []byte("fake fnos binary\n"), 0o755); err != nil {
		t.Fatalf("write fake binary: %v", err)
	}

	fpkPath := filepath.Join(tmp, "download-server-fnos-x86.fpk")
	if err := BuildFPK(binPath, fpkPath, "piko-download-server", "download-server-9.9.9-2026-web-ui", "x86"); err != nil {
		t.Fatalf("BuildFPK: %v", err)
	}
	info, err := os.Stat(fpkPath)
	if err != nil || info.Size() == 0 {
		t.Fatalf("stat fpk: %v", err)
	}

	raw, err := os.ReadFile(fpkPath)
	if err != nil {
		t.Fatalf("read fpk: %v", err)
	}
	outer := readGzipTar(t, raw)
	for _, name := range []string{
		"manifest",
		"app.tgz",
		"cmd/main",
		"cmd/common",
		"cmd/installer",
		"cmd/service-setup",
		"cmd/install_init",
		"cmd/install_callback",
		"cmd/upgrade_init",
		"cmd/upgrade_callback",
		"cmd/uninstall_init",
		"cmd/uninstall_callback",
		"config/privilege",
		"config/resource",
		"wizard/uninstall",
		"ui/config",
		"ui/images/64.png",
		"ui/images/256.png",
		"piko-download-server.sc",
		"health.json",
		"ICON.PNG",
		"ICON_256.PNG",
	} {
		if _, ok := outer[name]; !ok {
			t.Fatalf("fpk missing %q", name)
		}
	}

	manifest := parseManifest(string(outer["manifest"].body))
	for key, expected := range map[string]string{
		"appname":               "piko-download-server",
		"version":               "9.9.9-2026-web-ui",
		"platform":              "x86",
		"service_port":          "7788",
		"source":                "thirdparty",
		"desktop_applaunchname": "piko-download-server.Application",
	} {
		if manifest[key] != expected {
			t.Fatalf("manifest.%s = %q, want %q", key, manifest[key], expected)
		}
	}
	sum := md5.Sum(outer["app.tgz"].body)
	if manifest["checksum"] != hex.EncodeToString(sum[:]) {
		t.Fatalf("manifest checksum mismatch: %q", manifest["checksum"])
	}

	appFiles := readGzipTar(t, outer["app.tgz"].body)
	if _, ok := appFiles["./bin/download-server"]; !ok {
		t.Fatal("app.tgz missing ./bin/download-server")
	}
	if string(appFiles["./bin/download-server"].body) != "fake fnos binary\n" {
		t.Fatal("app.tgz binary content mismatch")
	}

	for name, entry := range map[string]tarEntry{
		"cmd/main":          outer["cmd/main"],
		"cmd/common":        outer["cmd/common"],
		"cmd/installer":     outer["cmd/installer"],
		"cmd/service-setup": outer["cmd/service-setup"],
	} {
		scriptPath := filepath.Join(tmp, strings.ReplaceAll(name, "/", "_"))
		if err := os.WriteFile(scriptPath, entry.body, 0o755); err != nil {
			t.Fatalf("write script: %v", err)
		}
		if bash, err := exec.LookPath("bash"); err == nil {
			if out, err := exec.Command(bash, "-n", scriptPath).CombinedOutput(); err != nil {
				t.Fatalf("bash -n %s failed: %v\n%s", name, err, out)
			}
		}
	}

	var ui struct {
		URL map[string]json.RawMessage `json:".url"`
	}
	if err := json.Unmarshal(outer["ui/config"].body, &ui); err != nil {
		t.Fatalf("ui/config is not valid JSON: %v", err)
	}
	if _, ok := ui.URL[manifest["desktop_applaunchname"]]; !ok {
		t.Fatalf("ui/config missing launch name %q", manifest["desktop_applaunchname"])
	}
	for _, name := range []string{"config/privilege", "config/resource", "health.json", "wizard/uninstall"} {
		var decoded any
		if err := json.Unmarshal(outer[name].body, &decoded); err != nil {
			t.Fatalf("%s is not valid JSON: %v", name, err)
		}
	}
}

func parseManifest(raw string) map[string]string {
	result := map[string]string{}
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		result[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	return result
}

type tarEntry struct {
	mode int64
	body []byte
}

func readGzipTar(t *testing.T, raw []byte) map[string]tarEntry {
	t.Helper()
	gz, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("open gzip: %v", err)
	}
	defer gz.Close()

	result := map[string]tarEntry{}
	tr := tar.NewReader(gz)
	for {
		header, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("read tar: %v", err)
		}
		if header.Typeflag != tar.TypeReg {
			continue
		}
		body, err := io.ReadAll(tr)
		if err != nil {
			t.Fatalf("read tar entry %s: %v", header.Name, err)
		}
		result[header.Name] = tarEntry{mode: header.Mode & 0o777, body: body}
	}
	return result
}
