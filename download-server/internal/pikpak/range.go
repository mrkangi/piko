package pikpak

import (
	"fmt"
	"strconv"
	"strings"
)

type ContentRange struct {
	Start int64
	End   int64
	Total int64
}

func ParseContentRange(header string) (ContentRange, error) {
	trimmed := strings.TrimSpace(header)
	if !strings.HasPrefix(strings.ToLower(trimmed), "bytes ") {
		return ContentRange{}, fmt.Errorf("unsupported Content-Range unit")
	}
	spec := strings.TrimSpace(trimmed[len("bytes "):])
	slash := strings.IndexByte(spec, '/')
	if slash <= 0 || slash == len(spec)-1 {
		return ContentRange{}, fmt.Errorf("malformed Content-Range")
	}
	rangePart := strings.TrimSpace(spec[:slash])
	totalPart := strings.TrimSpace(spec[slash+1:])
	if totalPart == "*" {
		return ContentRange{}, fmt.Errorf("unknown total length in Content-Range")
	}
	total, err := strconv.ParseInt(totalPart, 10, 64)
	if err != nil || total <= 0 {
		return ContentRange{}, fmt.Errorf("invalid Content-Range total")
	}
	dash := strings.IndexByte(rangePart, '-')
	if dash <= 0 || dash == len(rangePart)-1 {
		return ContentRange{}, fmt.Errorf("invalid Content-Range span")
	}
	start, err := strconv.ParseInt(strings.TrimSpace(rangePart[:dash]), 10, 64)
	if err != nil || start < 0 {
		return ContentRange{}, fmt.Errorf("invalid Content-Range start")
	}
	end, err := strconv.ParseInt(strings.TrimSpace(rangePart[dash+1:]), 10, 64)
	if err != nil || end < start || end >= total {
		return ContentRange{}, fmt.Errorf("invalid Content-Range end")
	}
	return ContentRange{Start: start, End: end, Total: total}, nil
}
