FROM node:22-alpine AS web-builder
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM golang:1.26-alpine AS builder
ARG VERSION=dev
ARG BUILD_TIME=unknown
ARG UPDATE_REPO=NihilDigit/piko
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
COPY --from=web-builder /internal/web/dist ./internal/web/dist
RUN CGO_ENABLED=0 GOOS=linux go build -tags embed_ui -trimpath \
    -ldflags="-s -w -X main.version=${VERSION} -X main.buildTime=${BUILD_TIME} -X main.updateRepo=${UPDATE_REPO}" \
    -o /out/download-server ./cmd/server

FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -g 10001 -S piko && \
    adduser -u 10001 -S -G piko piko && \
    mkdir -p /data && \
    chown -R 10001:10001 /data

COPY --from=builder /out/download-server /usr/local/bin/download-server

USER 10001:10001
ENV HOME=/data \
    DOWNLOAD_SERVER_HOST=0.0.0.0 \
    DOWNLOAD_SERVER_PORT=7788 \
    DOWNLOAD_SERVER_DATA_DIR=/data/.pikpak/downloader

VOLUME ["/data"]
EXPOSE 7788

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:7788/healthz >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/download-server"]
