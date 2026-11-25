package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/md5"
	"embed"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"os"
	"path"
	"regexp"
	"strings"
	"time"
)

//go:embed assets/*
var assetFS embed.FS

var nonVersionChars = regexp.MustCompile(`[^A-Za-z0-9.+-]+`)
var fourPartVersion = regexp.MustCompile(`^([0-9]+\.[0-9]+\.[0-9]+)\.([0-9]+)$`)

type fileEntry struct {
	name string
	mode int64
	body []byte
	dir  bool
}

func main() {
	var (
		binaryPath = flag.String("binary", "", "path to the compiled linux download-server binary")
		outputPath = flag.String("output", "", "output .fpk file path")
		version    = flag.String("version", "0.1.0", "application version")
		platform   = flag.String("platform", "x86", "fnOS platform: x86 or arm")
		pkgName    = flag.String("package", "piko-download-server", "fnOS appname")
	)
	flag.Parse()

	if *binaryPath == "" || *outputPath == "" {
		fmt.Fprintln(os.Stderr, "usage: fnospkg -binary <path> -output <path> [-version <ver>] [-platform <x86|arm>]")
		os.Exit(2)
	}
	if err := BuildFPK(*binaryPath, *outputPath, *pkgName, *version, *platform); err != nil {
		fmt.Fprintf(os.Stderr, "build fnos package: %v\n", err)
		os.Exit(1)
	}
}

func SanitizeVersion(raw string) string {
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
		return "0.1.0-" + v
	}
	if match := fourPartVersion.FindStringSubmatch(v); match != nil {
		return match[1] + "-" + match[2]
	}
	return v
}

func validatePlatform(platform string) error {
	switch platform {
	case "x86", "arm":
		return nil
	default:
		return fmt.Errorf("unsupported fnOS platform %q", platform)
	}
}

func BuildFPK(binaryPath, outputPath, pkgName, rawVersion, platform string) error {
	if err := validatePlatform(platform); err != nil {
		return err
	}
	binBytes, err := os.ReadFile(binaryPath)
	if err != nil {
		return fmt.Errorf("read binary: %w", err)
	}
	if len(binBytes) == 0 {
		return fmt.Errorf("binary %s is empty", binaryPath)
	}

	appTGZ, err := buildTarGz([]fileEntry{
		{name: "./bin/", mode: 0o755, dir: true},
		{name: "./bin/download-server", mode: 0o755, body: binBytes},
	}, fixedTime())
	if err != nil {
		return fmt.Errorf("build app.tgz: %w", err)
	}

	version := SanitizeVersion(rawVersion)
	manifest := buildManifest(pkgName, version, platform, hex.EncodeToString(md5Sum(appTGZ)))

	privilege, err := assetFS.ReadFile("assets/privilege")
	if err != nil {
		return fmt.Errorf("read privilege: %w", err)
	}
	resource, err := assetFS.ReadFile("assets/resource")
	if err != nil {
		return fmt.Errorf("read resource: %w", err)
	}
	uiConfig, err := assetFS.ReadFile("assets/ui-config")
	if err != nil {
		return fmt.Errorf("read ui config: %w", err)
	}
	firewall, err := assetFS.ReadFile("assets/piko-download-server.sc")
	if err != nil {
		return fmt.Errorf("read firewall config: %w", err)
	}
	health, err := assetFS.ReadFile("assets/health.json")
	if err != nil {
		return fmt.Errorf("read health config: %w", err)
	}
	uninstall, err := assetFS.ReadFile("assets/uninstall")
	if err != nil {
		return fmt.Errorf("read uninstall wizard: %w", err)
	}
	icon64, err := assetFS.ReadFile("assets/icon-64.png")
	if err != nil {
		return fmt.Errorf("read small icon: %w", err)
	}
	icon256, err := assetFS.ReadFile("assets/icon-256.png")
	if err != nil {
		return fmt.Errorf("read large icon: %w", err)
	}

	scriptNames := []string{
		"main",
		"common",
		"installer",
		"service-setup",
		"install_init",
		"install_callback",
		"upgrade_init",
		"upgrade_callback",
		"uninstall_init",
		"uninstall_callback",
		"config_init",
		"config_callback",
	}
	scripts := map[string][]byte{}
	for _, name := range scriptNames {
		body, err := assetFS.ReadFile(path.Join("assets", name))
		if err != nil {
			return fmt.Errorf("read script %s: %w", name, err)
		}
		scripts[name] = body
	}

	entries := []fileEntry{
		{name: "manifest", mode: 0o644, body: []byte(manifest)},
		{name: "app.tgz", mode: 0o644, body: appTGZ},
		{name: "cmd/", mode: 0o755, dir: true},
		{name: "config/", mode: 0o755, dir: true},
		{name: "config/privilege", mode: 0o644, body: privilege},
		{name: "config/resource", mode: 0o644, body: resource},
		{name: "wizard/", mode: 0o755, dir: true},
		{name: "wizard/uninstall", mode: 0o644, body: uninstall},
		{name: "ui/", mode: 0o755, dir: true},
		{name: "ui/config", mode: 0o644, body: uiConfig},
		{name: "ui/images/", mode: 0o755, dir: true},
		{name: "ui/images/64.png", mode: 0o644, body: icon64},
		{name: "ui/images/256.png", mode: 0o644, body: icon256},
		{name: "piko-download-server.sc", mode: 0o644, body: firewall},
		{name: "health.json", mode: 0o644, body: health},
		{name: "ICON.PNG", mode: 0o644, body: icon64},
		{name: "ICON_256.PNG", mode: 0o644, body: icon256},
	}
	for _, name := range scriptNames {
		entries = append(entries, fileEntry{
			name: path.Join("cmd", name),
			mode: 0o755,
			body: scripts[name],
		})
	}

	fpk, err := buildTarGz(entries, fixedTime())
	if err != nil {
		return fmt.Errorf("build fpk: %w", err)
	}
	return os.WriteFile(outputPath, fpk, 0o644)
}

func buildManifest(pkgName, version, platform, checksum string) string {
	lines := [][2]string{
		{"appname", pkgName},
		{"version", version},
		{"display_name", "Piko Download Server"},
		{"platform", platform},
		{"maintainer", "Piko"},
		{"maintainer_url", "https://github.com/NihilDigit/piko"},
		{"distributor", "Piko"},
		{"distributor_url", "https://github.com/mrkangi/piko"},
		{"desktop_uidir", "ui"},
		{"desktop_applaunchname", pkgName + ".Application"},
		{"service_port", "7788"},
		{"desc", "PikPak 远程下载与目录同步服务，含 Web 管理界面。"},
		{"source", "thirdparty"},
		{"checksum", checksum},
	}
	var b strings.Builder
	for _, line := range lines {
		fmt.Fprintf(&b, "%-21s = %s\n", line[0], line[1])
	}
	return b.String()
}

func md5Sum(data []byte) []byte {
	sum := md5.Sum(data)
	return sum[:]
}

func fixedTime() time.Time {
	return time.Unix(1700000000, 0).UTC()
}

func buildTarGz(entries []fileEntry, modTime time.Time) ([]byte, error) {
	var buf bytes.Buffer
	gz, err := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	if err != nil {
		return nil, err
	}
	gz.ModTime = modTime
	if err := writeTar(gz, entries, modTime); err != nil {
		_ = gz.Close()
		return nil, err
	}
	if err := gz.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeTar(w io.Writer, entries []fileEntry, modTime time.Time) error {
	tw := tar.NewWriter(w)
	for _, entry := range entries {
		header := &tar.Header{
			Name:     entry.name,
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
		if entry.dir {
			header.Typeflag = tar.TypeDir
			header.Size = 0
			if !strings.HasSuffix(header.Name, "/") {
				header.Name += "/"
			}
		}
		if err := tw.WriteHeader(header); err != nil {
			return err
		}
		if !entry.dir && len(entry.body) > 0 {
			if _, err := tw.Write(entry.body); err != nil {
				return err
			}
		}
	}
	return tw.Close()
}
