# syntax=docker/dockerfile:1
#
# Build from the repository root:
#   docker build -f backend/Dockerfile -t prodl .
#
# The image serves the JSON/WebSocket API and the static SPA from one origin.
FROM python:3.12-slim

ARG DEBIAN_FRONTEND=noninteractive
ARG INSTALL_DENO=true
ARG DENO_VERSION=2.5.6
ARG TARGETARCH

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PRODL_DATA_DIR=/data \
    PRODL_SERVE_FRONTEND=1

# ffmpeg does the merging/audio extraction; ca-certificates for HTTPS;
# curl backs the container healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*

# Optional JavaScript runtime for YouTube signature challenges. Pinned release
# tarball (no curl | sh) and architecture aware so the same Dockerfile builds
# on amd64 and arm64.
RUN if [ "$INSTALL_DENO" = "true" ]; then \
        case "${TARGETARCH:-amd64}" in \
            arm64) DENO_ARCH="aarch64-unknown-linux-gnu" ;; \
            *)     DENO_ARCH="x86_64-unknown-linux-gnu" ;; \
        esac; \
        curl -fsSL -o /tmp/deno.zip \
            "https://github.com/denoland/deno/releases/download/v${DENO_VERSION}/deno-${DENO_ARCH}.zip"; \
        unzip -q -o /tmp/deno.zip -d /usr/local/bin; \
        chmod 0755 /usr/local/bin/deno; \
        rm -f /tmp/deno.zip; \
        deno --version; \
    fi

WORKDIR /app

COPY backend/requirements.txt ./backend/requirements.txt
RUN pip install --no-cache-dir -r backend/requirements.txt

COPY packages/prodl-core ./packages/prodl-core
RUN pip install --no-cache-dir ./packages/prodl-core

COPY backend ./backend
COPY frontend ./frontend

RUN useradd --create-home --uid 10001 prodl \
 && mkdir -p /data \
 && chown -R prodl:prodl /data /app
USER prodl

WORKDIR /app/backend
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/api/health > /dev/null || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
