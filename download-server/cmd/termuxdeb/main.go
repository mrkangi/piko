package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"flag"
	"fmt"
	"io"
	"os"
	"path"
	"regexp"
	"strings"
	"time"
)

const (
	termuxPrefix = "data/data/com.termux/files/usr"
	termuxHome   = "/data/data/com.termux/files/home"
)

var nonVersionChars = regexp.MustCompile(`[^A-Za-z0-9.+~-]+`)

type debFileEntry struct {
	relPath string
	mode    int64
	body    []byte
	isDir   bool
}

func main() {
	var (
		binaryPath = flag.String("binary", "", "path to compiled android-arm64 download-server binary")
		outputPath = flag.String("output", "", "output .deb file path")
		version    = flag.String("version", "0.1.0", "package version")
		pkgName    = flag.String("package", "piko-download-server", "Debian package name")
		arch       = flag.String("arch", "aarch64", "Termux Debian architecture")
	)
	flag.Parse()

	if *binaryPath == "" || *outputPath == "" {
		fmt.Fprintln(os.Stderr, "usage: termuxdeb -binary <path> -output <path> [-version <ver>]")
		os.Exit(2)
	}

	if err := BuildTermuxDeb(*binaryPath, *outputPath, *pkgName, *version, *arch); err != nil {
		fmt.Fprintf(os.Stderr, "build termux deb: %v\n", err)
		os.Exit(1)
	}
}

func SanitizeDebVersion(raw string) string {
	v := strings.TrimSpace(raw)
	v = strings.TrimPrefix(v, "download-server-")
	if v == "download-server" {
		v = ""
	}
	v = strings.TrimPrefix(v, "v")
	v = nonVersionChars.ReplaceAllString(v, "-")
	v = strings.Trim(v, "-.")
	if v == "" {
		return "0.1.0"
	}
	if v[0] < '0' || v[0] > '9' {
		return "0.1.0~" + v
	}
	return v
}

func BuildTermuxDeb(binaryPath, outputPath, pkgName, rawVersion, arch string) error {
	binBytes, err := os.ReadFile(binaryPath)
	if err != nil {
		return fmt.Errorf("read binary: %w", err)
	}
	if len(binBytes) == 0 {
		return fmt.Errorf("binary %s is empty", binaryPath)
	}

	version := SanitizeDebVersion(rawVersion)
	fixedTime := time.Unix(1700000000, 0).UTC()

	defaultEnv := strings.Join([]string{
		"# PikPak Download Server environment configuration for Termux",
		"DOWNLOAD_SERVER_HOST=\"0.0.0.0\"",
		"DOWNLOAD_SERVER_PORT=\"7788\"",
		"DOWNLOAD_SERVER_DATA_DIR=\"" + termuxHome + "/.pikpak/downloader\"",
		"",
	}, "\n")

	runScript := strings.Join([]string{
		"#!/" + "/" + termuxPrefix + "/bin/sh",
		"exec 2>&1",
		"export PREFIX=\"/" + termuxPrefix + "\"",
		"export HOME=\"" + termuxHome + "\"",
		"if [ -f \"$PREFIX/etc/default/download-server\" ]; then",
		"  . \"$PREFIX/etc/default/download-server\"",
		"fi",
		"export DOWNLOAD_SERVER_HOST=\"${DOWNLOAD_SERVER_HOST:-0.0.0.0}\"",
		"export DOWNLOAD_SERVER_PORT=\"${DOWNLOAD_SERVER_PORT:-7788}\"",
		"export DOWNLOAD_SERVER_DATA_DIR=\"${DOWNLOAD_SERVER_DATA_DIR:-$HOME/.pikpak/downloader}\"",
		"exec \"$PREFIX/bin/download-server\"",
		"",
	}, "\n")

	logRunScript := strings.Join([]string{
		"#!/" + "/" + termuxPrefix + "/bin/sh",
		"svlogger=\"/" + termuxPrefix + "/share/termux-services/svlogger\"",
		"exec \"${svlogger}\" \"$@\"",
		"",
	}, "\n")

	// Important for Termux dpkg: directory entries must start at ./data/data/com.termux/files/usr
	// because parent directories (/data, /data/data) are owned by Android system and cannot be chmod/utime'd.
	dataEntries := []debFileEntry{
		{relPath: "./" + termuxPrefix, mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "bin"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "bin/download-server"), mode: 0o755, body: binBytes},
		{relPath: "./" + path.Join(termuxPrefix, "etc"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "etc/default"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "etc/default/download-server"), mode: 0o644, body: []byte(defaultEnv)},
		{relPath: "./" + path.Join(termuxPrefix, "var"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/log"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/log/sv"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/log/sv/download-server"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/service"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/service/download-server"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/service/download-server/run"), mode: 0o755, body: []byte(runScript)},
		{relPath: "./" + path.Join(termuxPrefix, "var/service/download-server/log"), mode: 0o755, isDir: true},
		{relPath: "./" + path.Join(termuxPrefix, "var/service/download-server/log/run"), mode: 0o755, body: []byte(logRunScript)},
	}

	dataTarGz, err := buildTarGz(dataEntries, fixedTime)
	if err != nil {
		return fmt.Errorf("build data.tar.gz: %w", err)
	}

	installedSizeKB := (len(binBytes) + len(defaultEnv) + len(runScript) + len(logRunScript) + 1023) / 1024
	controlText := strings.Join([]string{
		fmt.Sprintf("Package: %s", pkgName),
		fmt.Sprintf("Version: %s", version),
		fmt.Sprintf("Architecture: %s", arch),
		"Maintainer: Piko <https://github.com/NihilDigit/piko>",
		fmt.Sprintf("Installed-Size: %d", installedSizeKB),
		"Depends: termux-services",
		"Section: net",
		"Priority: optional",
		"Homepage: https://github.com/NihilDigit/piko",
		"Description: Standalone PikPak download and folder sync service for Termux",
		" Runs download-server as a persistent Termux runit service with embedded Web UI.",
		"",
	}, "\n")

	conffilesText := "/" + path.Join(termuxPrefix, "etc/default/download-server") + "\n"

	postinstScript := strings.Join([]string{
		"#!/" + "/" + termuxPrefix + "/bin/sh",
		"set -e",
		"export PREFIX=\"/" + termuxPrefix + "\"",
		"export SVDIR=\"${SVDIR:-$PREFIX/var/service}\"",
		"mkdir -p \"$PREFIX/var/log/sv/download-server\"",
		"if command -v termux-wake-lock >/dev/null 2>&1; then",
		"  termux-wake-lock >/dev/null 2>&1 || true",
		"fi",
		"if [ -f \"$PREFIX/etc/profile.d/start-services.sh\" ]; then",
		"  . \"$PREFIX/etc/profile.d/start-services.sh\" >/dev/null 2>&1 || true",
		"fi",
		"if command -v sv-enable >/dev/null 2>&1; then",
		"  sv-enable download-server >/dev/null 2>&1 || true",
		"fi",
		"if command -v sv >/dev/null 2>&1; then",
		"  sv up download-server >/dev/null 2>&1 || true",
		"fi",
		"echo \"==> piko-download-server deployed to Termux.\"",
		"echo \"    Config:  $PREFIX/etc/default/download-server\"",
		"echo \"    Control: sv status|up|down|restart download-server\"",
		"echo \"    Logs:    cat $PREFIX/var/log/sv/download-server/current\"",
		"echo \"    Web UI:  http://127.0.0.1:7788\"",
		"",
	}, "\n")

	prermScript := strings.Join([]string{
		"#!/" + "/" + termuxPrefix + "/bin/sh",
		"set -e",
		"export PREFIX=\"/" + termuxPrefix + "\"",
		"export SVDIR=\"${SVDIR:-$PREFIX/var/service}\"",
		"if command -v sv >/dev/null 2>&1; then",
		"  sv down download-server >/dev/null 2>&1 || true",
		"fi",
		"if command -v sv-disable >/dev/null 2>&1; then",
		"  sv-disable download-server >/dev/null 2>&1 || true",
		"fi",
		"",
	}, "\n")

	controlEntries := []debFileEntry{
		{relPath: "./", mode: 0o755, isDir: true},
		{relPath: "./control", mode: 0o644, body: []byte(controlText)},
		{relPath: "./conffiles", mode: 0o644, body: []byte(conffilesText)},
		{relPath: "./postinst", mode: 0o755, body: []byte(postinstScript)},
		{relPath: "./prerm", mode: 0o755, body: []byte(prermScript)},
	}

	controlTarGz, err := buildTarGz(controlEntries, fixedTime)
	if err != nil {
		return fmt.Errorf("build control.tar.gz: %w", err)
	}

	var debBuf bytes.Buffer
	debBuf.WriteString("!<arch>\n")
	writeArMember(&debBuf, "debian-binary", fixedTime, []byte("2.0\n"))
	writeArMember(&debBuf, "control.tar.gz", fixedTime, controlTarGz)
	writeArMember(&debBuf, "data.tar.gz", fixedTime, dataTarGz)

	return os.WriteFile(outputPath, debBuf.Bytes(), 0o644)
}

func buildTarGz(entries []debFileEntry, modTime time.Time) ([]byte, error) {
	var buf bytes.Buffer
	gz, err := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	if err != nil {
		return nil, err
	}
	gz.ModTime = modTime
	tw := tar.NewWriter(gz)

	for _, entry := range entries {
		hdr := &tar.Header{
			Name:     entry.relPath,
			Mode:     entry.mode,
			Uid:      0,
			Gid:      0,
			Uname:    "root",
			Gname:    "root",
			ModTime:  modTime,
			Format:   tar.FormatGNU,
			Typeflag: tar.TypeReg,
			Size:     int64(len(entry.body)),
		}
		if entry.isDir {
			hdr.Typeflag = tar.TypeDir
			hdr.Size = 0
			if !strings.HasSuffix(hdr.Name, "/") {
				hdr.Name += "/"
			}
		}
		if err := tw.WriteHeader(hdr); err != nil {
			return nil, err
		}
		if !entry.isDir && len(entry.body) > 0 {
			if _, err := io.Copy(tw, bytes.NewReader(entry.body)); err != nil {
				return nil, err
			}
		}
	}

	if err := tw.Close(); err != nil {
		return nil, err
	}
	if err := gz.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeArMember(w *bytes.Buffer, name string, modTime time.Time, data []byte) {
	header := fmt.Sprintf(
		"%-16s%-12d%-6d%-6d%-8s%-10d`\n",
		name,
		modTime.Unix(),
		0,
		0,
		"100644",
		len(data),
	)
	w.WriteString(header)
	w.Write(data)
	if len(data)%2 != 0 {
		w.WriteByte('\n')
	}
}
