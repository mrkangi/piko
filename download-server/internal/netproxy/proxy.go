package netproxy

import (
	"fmt"
	"net/url"
	"strings"
)

func Normalize(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", nil
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return "", fmt.Errorf("代理地址格式无效: %w", err)
	}
	scheme := strings.ToLower(strings.TrimSpace(parsed.Scheme))
	switch scheme {
	case "http", "https", "socks5", "socks5h":
	default:
		return "", fmt.Errorf("代理协议仅支持 http、https、socks5")
	}
	if strings.TrimSpace(parsed.Hostname()) == "" {
		return "", fmt.Errorf("代理地址缺少主机名")
	}
	if parsed.Path != "" && parsed.Path != "/" {
		return "", fmt.Errorf("代理地址不能包含路径")
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", fmt.Errorf("代理地址不能包含查询参数或片段")
	}
	if (scheme == "socks5" || scheme == "socks5h") && parsed.Port() == "" {
		return "", fmt.Errorf("SOCKS5 代理必须指定端口")
	}
	parsed.Scheme = scheme
	parsed.Path = ""
	return parsed.String(), nil
}

func Mask(raw string) (string, error) {
	normalized, err := Normalize(raw)
	if err != nil || normalized == "" {
		return "", err
	}
	parsed, err := url.Parse(normalized)
	if err != nil {
		return "", err
	}
	if parsed.User != nil {
		encodedUser := url.User(parsed.User.Username()).String()
		if _, hasPassword := parsed.User.Password(); hasPassword {
			encodedUser += ":***"
		}
		parsed.User = nil
		return parsed.Scheme + "://" + encodedUser + "@" + parsed.Host, nil
	}
	return parsed.String(), nil
}
