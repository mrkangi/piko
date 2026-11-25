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
		outputPath = flag.String("output", "", "output .spk file path")
		version    = flag.String("version", "0.1.0", "package version")
		arch       = flag.String("arch", "x86_64", "Synology architecture: x86_64, armv8 or armv7")
		pkgName    = flag.String("package", "piko-download-server", "Synology package name")
	)
	flag.Parse()

	if *binaryPath == "" || *outputPath == "" {
		fmt.Fprintln(os.Stderr, "usage: synopkg -binary <path> -output <path> [-version <ver>] [-arch <arch>]")
		os.Exit(2)
	}
	if err := BuildSPK(*binaryPath, *outputPath, *pkgName, *version, *arch); err != nil {
		fmt.Fprintf(os.Stderr, "build synology package: %v\n", err)
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

func archField(arch string) (string, error) {
	switch arch {
	case "x86_64":
		return strings.Join([]string{
			"apollolake", "avoton", "braswell", "broadwell", "broadwellnk",
			"broadwellnkv2", "broadwellntbap", "bromolow", "cedarview", "denverton",
			"dockerx64", "epyc7002", "epyc7003", "epyc7003ntb", "geminilake",
			"geminilakenk", "grantley", "icelaked", "kvmx64", "purley", "r1000",
			"r1000nk", "v1000", "v1000nk", "x86_64",
		}, " "), nil
	case "armv8":
		return "armada37xx rtd1296 rtd1619b", nil
	case "armv7":
		return "alpine alpine4k armada370 armada375 armada38x armadaxp monaco comcerto2k", nil
	default:
		return "", fmt.Errorf("unsupported Synology architecture %q", arch)
	}
}

func BuildSPK(binaryPath, outputPath, pkgName, rawVersion, arch string) error {
	archList, err := archField(arch)
	if err != nil {
		return err
	}
	binBytes, err := os.ReadFile(binaryPath)
	if err != nil {
		return fmt.Errorf("read binary: %w", err)
	}
	if len(binBytes) == 0 {
		return fmt.Errorf("binary %s is empty", binaryPath)
	}

	appConfig, err := assetFS.ReadFile("assets/app-config.json")
	if err != nil {
		return fmt.Errorf("read app config: %w", err)
	}
	firewallConfig, err := assetFS.ReadFile("assets/piko-download-server.sc")
	if err != nil {
		return fmt.Errorf("read firewall config: %w", err)
	}
	icon64, err := assetFS.ReadFile("assets/icon-64.png")
	if err != nil {
		return fmt.Errorf("read small package icon: %w", err)
	}
	icon256, err := assetFS.ReadFile("assets/icon-256.png")
	if err != nil {
		return fmt.Errorf("read large package icon: %w", err)
	}

	packageEntries := []fileEntry{
		{name: "./bin/", mode: 0o755, dir: true},
		{name: "./bin/download-server", mode: 0o755, body: binBytes},
		{name: "./app/", mode: 0o755, dir: true},
		{name: "./app/config", mode: 0o644, body: appConfig},
		{name: "./app/piko-download-server.sc", mode: 0o644, body: firewallConfig},
		{name: "./app/images/", mode: 0o755, dir: true},
		{name: "./app/images/piko-download-server-64.png", mode: 0o644, body: icon64},
		{name: "./app/images/piko-download-server-256.png", mode: 0o644, body: icon256},
	}
	packageTGZ, err := buildTarGz(packageEntries, fixedTime())
	if err != nil {
		return fmt.Errorf("build package.tgz: %w", err)
	}

	version := SanitizeVersion(rawVersion)
	checksum := md5.Sum(packageTGZ)
	info := buildInfo(pkgName, version, archList, hex.EncodeToString(checksum[:]))

	scripts := map[string][]byte{}
	for _, name := range []string{
		"common.sh",
		"start-stop-status",
		"preinst",
		"postinst",
		"preupgrade",
		"postupgrade",
		"preuninst",
		"postuninst",
	} {
		body, err := assetFS.ReadFile(path.Join("assets", name))
		if err != nil {
			return fmt.Errorf("read script %s: %w", name, err)
		}
		scripts[name] = body
	}
	privilege, err := assetFS.ReadFile("assets/privilege.json")
	if err != nil {
		return fmt.Errorf("read privilege file: %w", err)
	}
	resource, err := assetFS.ReadFile("assets/resource.json")
	if err != nil {
		return fmt.Errorf("read resource file: %w", err)
	}
	wizard, err := assetFS.ReadFile("assets/install_uifile")
	if err != nil {
		return fmt.Errorf("read install wizard: %w", err)
	}

	spkEntries := []fileEntry{
		{name: "INFO", mode: 0o644, body: []byte(info)},
		{name: "package.tgz", mode: 0o644, body: packageTGZ},
		{name: "scripts/", mode: 0o755, dir: true},
		{name: "scripts/common.sh", mode: 0o644, body: scripts["common.sh"]},
		{name: "scripts/start-stop-status", mode: 0o755, body: scripts["start-stop-status"]},
		{name: "scripts/preinst", mode: 0o755, body: scripts["preinst"]},
		{name: "scripts/postinst", mode: 0o755, body: scripts["postinst"]},
		{name: "scripts/preupgrade", mode: 0o755, body: scripts["preupgrade"]},
		{name: "scripts/postupgrade", mode: 0o755, body: scripts["postupgrade"]},
		{name: "scripts/preuninst", mode: 0o755, body: scripts["preuninst"]},
		{name: "scripts/postuninst", mode: 0o755, body: scripts["postuninst"]},
		{name: "conf/", mode: 0o755, dir: true},
		{name: "conf/privilege", mode: 0o644, body: privilege},
		{name: "conf/resource", mode: 0o644, body: resource},
		{name: "WIZARD_UIFILES/", mode: 0o755, dir: true},
		{name: "WIZARD_UIFILES/install_uifile", mode: 0o644, body: wizard},
		{name: "PACKAGE_ICON.PNG", mode: 0o644, body: icon64},
		{name: "PACKAGE_ICON_256.PNG", mode: 0o644, body: icon256},
	}

	spk, err := buildTar(spkEntries, fixedTime())
	if err != nil {
		return fmt.Errorf("build spk archive: %w", err)
	}
	return os.WriteFile(outputPath, spk, 0o644)
}

func buildInfo(pkgName, version, arch, checksum string) string {
	return strings.Join([]string{
		fmt.Sprintf(`package="%s"`, pkgName),
		fmt.Sprintf(`version="%s"`, version),
		`description="PikPak remote download and folder sync service with Web UI"`,
		`description_chs="PikPak 远程下载与目录同步服务，含 Web 管理界面"`,
		`description_cht="PikPak 遠端下載與目錄同步服務，含 Web 管理介面"`,
		`description_enu="PikPak remote download and folder sync service with Web UI"`,
		fmt.Sprintf(`arch="%s"`, arch),
		`maintainer="Piko"`,
		`maintainer_url="https://github.com/NihilDigit/piko"`,
		`distributor="Piko"`,
		`distributor_url="https://github.com/NihilDigit/piko"`,
		`os_min_ver="7.0-40000"`,
		`displayname="Piko Download Server"`,
		`startable="yes"`,
		`dsmuidir="app"`,
		`dsmappname="com.piko.downloadserver"`,
		`adminprotocol="http"`,
		`adminport="7788"`,
		`adminurl="/"`,
		`support_conf_folder="yes"`,
		fmt.Sprintf(`checksum="%s"`, checksum),
		"",
	}, "\n")
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

func buildTar(entries []fileEntry, modTime time.Time) ([]byte, error) {
	var buf bytes.Buffer
	if err := writeTar(&buf, entries, modTime); err != nil {
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
