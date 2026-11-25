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

func TestValidateArch(t *testing.T) {
	for _, arch := range []string{"x86_64", "armv8", "armv7"} {
		if _, err := archField(arch); err != nil {
			t.Fatalf("archField(%q): %v", arch, err)
		}
	}
	if _, err := archField("mips"); err == nil {
		t.Fatal("expected unsupported architecture error")
	}
	if got, _ := archField("armv8"); !strings.Contains(got, "rtd1296") {
		t.Fatalf("ARM64 package must use DSM ARMv8 machine archs: %q", got)
	}
}

func TestBuildSPK(t *testing.T) {
	tmp := t.TempDir()
	binPath := filepath.Join(tmp, "download-server-linux-amd64")
	if err := os.WriteFile(binPath, []byte("fake binary\n"), 0o755); err != nil {
		t.Fatalf("write fake binary: %v", err)
	}

	spkPath := filepath.Join(tmp, "piko-download-server.spk")
	if err := BuildSPK(binPath, spkPath, "piko-download-server", "download-server-9.9.9-2026-web-ui", "x86_64"); err != nil {
		t.Fatalf("BuildSPK: %v", err)
	}
	info, err := os.Stat(spkPath)
	if err != nil || info.Size() == 0 {
		t.Fatalf("stat spk: %v", err)
	}

	raw, err := os.ReadFile(spkPath)
	if err != nil {
		t.Fatalf("read spk: %v", err)
	}
	outer := readTar(t, bytes.NewReader(raw))
	for _, name := range []string{
		"INFO",
		"package.tgz",
		"scripts/common.sh",
		"scripts/start-stop-status",
		"scripts/postinst",
		"conf/privilege",
		"conf/resource",
		"WIZARD_UIFILES/install_uifile",
		"PACKAGE_ICON.PNG",
		"PACKAGE_ICON_256.PNG",
	} {
		if _, ok := outer[name]; !ok {
			t.Fatalf("spk missing %q", name)
		}
	}
	if got := outer["scripts/start-stop-status"].mode; got != 0o755 {
		t.Fatalf("start-stop-status mode = %o, want 755", got)
	}
	if got := outer["scripts/common.sh"].mode; got != 0o644 {
		t.Fatalf("common.sh mode = %o, want 644", got)
	}

	infoText := string(outer["INFO"].body)
	for _, expected := range []string{
		`package="piko-download-server"`,
		`version="9.9.9-2026-web-ui"`,
		`arch="apollolake`,
		`os_min_ver="7.0-40000"`,
		`startable="yes"`,
		`adminport="7788"`,
		`support_conf_folder="yes"`,
	} {
		if !strings.Contains(infoText, expected) {
			t.Fatalf("INFO missing %q:\n%s", expected, infoText)
		}
	}
	sum := md5.Sum(outer["package.tgz"].body)
	if !strings.Contains(infoText, `checksum="`+hex.EncodeToString(sum[:])+`"`) {
		t.Fatalf("INFO checksum does not match package.tgz:\n%s", infoText)
	}

	packageFiles := readGzipTar(t, outer["package.tgz"].body)
	for _, name := range []string{
		"./bin/download-server",
		"./app/config",
		"./app/piko-download-server.sc",
		"./app/images/piko-download-server-64.png",
		"./app/images/piko-download-server-256.png",
	} {
		if _, ok := packageFiles[name]; !ok {
			t.Fatalf("package.tgz missing %q", name)
		}
	}
	if string(packageFiles["./bin/download-server"].body) != "fake binary\n" {
		t.Fatal("package.tgz binary content mismatch")
	}

	for name, entry := range map[string]tarEntry{
		"scripts/start-stop-status": outer["scripts/start-stop-status"],
		"scripts/postinst":          outer["scripts/postinst"],
		"scripts/common.sh":         outer["scripts/common.sh"],
	} {
		scriptPath := filepath.Join(tmp, filepath.Base(name))
		if err := os.WriteFile(scriptPath, entry.body, 0o755); err != nil {
			t.Fatalf("write script: %v", err)
		}
		if sh, err := exec.LookPath("sh"); err == nil {
			if out, err := exec.Command(sh, "-n", scriptPath).CombinedOutput(); err != nil {
				t.Fatalf("sh -n %s failed: %v\n%s", name, err, out)
			}
		}
	}

	for _, name := range []string{"conf/privilege", "conf/resource", "WIZARD_UIFILES/install_uifile"} {
		var decoded any
		if err := json.Unmarshal(outer[name].body, &decoded); err != nil {
			t.Fatalf("%s is not valid JSON: %v", name, err)
		}
	}
}

type tarEntry struct {
	mode int64
	body []byte
}

func readTar(t *testing.T, r io.Reader) map[string]tarEntry {
	t.Helper()
	result := map[string]tarEntry{}
	tr := tar.NewReader(r)
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

func readGzipTar(t *testing.T, raw []byte) map[string]tarEntry {
	t.Helper()
	gz, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("open package.tgz: %v", err)
	}
	defer gz.Close()
	return readTar(t, gz)
}
