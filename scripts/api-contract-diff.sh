#!/usr/bin/env bash
# IT-05: API 계약 정합성 (Swagger diff)
# 구현된 API(springdoc이 실코드에서 생성한 live spec)와 docs/api-contract/openapi.yaml을 비교한다.
# ponytail: oasdiff(docker 이미지)로 위임 — 커스텀 파서/클라이언트 안 만듦.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONTRACT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs/api-contract/openapi.yaml"
TMP_DIR="$(mktemp -d)"
LIVE="$TMP_DIR/live-openapi.json"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "[1/2] live spec 수집: $BASE_URL/v3/api-docs"
curl -fsS "$BASE_URL/v3/api-docs" -o "$LIVE"

echo "[2/2] oasdiff로 필드명·타입·필수여부 비교"
# ponytail: Git Bash(MSYS)의 자동 경로변환이 호스트/컨테이너 경로를 뒤섞는 걸 피하려고,
# 호스트 경로는 미리 cygpath로 윈도우 절대경로로 바꾸고, MSYS_NO_PATHCONV로 이후 변환을 전부 끈다
# (non-Windows/non-MSYS 환경에선 cygpath가 없으면 원본 경로를 그대로 씀 — 무해).
if command -v cygpath >/dev/null 2>&1; then
  CONTRACT="$(cygpath -w "$CONTRACT")"
  LIVE="$(cygpath -w "$LIVE")"
fi
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$CONTRACT:/specs/contract.yaml:ro" \
  -v "$LIVE:/specs/live.json:ro" \
  tufin/oasdiff breaking /specs/contract.yaml /specs/live.json
