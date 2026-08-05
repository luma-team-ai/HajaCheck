#!/usr/bin/env bash
# IT-05: API 변경 감지 (Swagger diff)
#
# 직전에 확정한 springdoc 스냅샷과 현재 코드의 live spec 을 비교해 API 변경 — 특히 클라이언트를
# 깨뜨리는 변경 — 을 잡는다.
#
# ponytail: 비교 기준을 수기 docs/api-contract/openapi.yaml 이 아니라 springdoc 스냅샷으로 잡는다.
# 팀이 API 계약의 기준 문서(SOT)를 스웨거로 전환했고, 수기 문서는 더 이상 코드에 맞춰 갱신하지
# 않는다 — 그걸 기준으로 삼으면 드리프트가 계속 쌓여 "불일치 0건"이 원리적으로 도달 불가능해진다.
# 양쪽이 같은 생성기의 출력이라, 수기 문서와 비교할 때 필요했던 오탐 필터(--flatten-params,
# --unmatch-path)도 더는 필요 없다. 표현 차이가 애초에 생기지 않기 때문이다.
#
# 사용법
#   docker compose up -d postgres redis spring
#   bash scripts/api-contract-diff.sh                   # 스냅샷 대비 변경 확인
#   bash scripts/api-contract-diff.sh --update-baseline # 릴리스 후 스냅샷 갱신(커밋할 것)
#   docker compose stop postgres redis spring
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$ROOT/docs/api-contract/springdoc-baseline.json"
TMP_DIR="$(mktemp -d)"
LIVE="$TMP_DIR/live-openapi.json"
trap 'rm -rf "$TMP_DIR"' EXIT

# 스프링을 띄우기 전에 먼저 걸러야 안내가 제때 나온다(스냅샷이 없으면 비교 자체가 성립 안 함).
if [ "${1:-}" != "--update-baseline" ] && [ ! -f "$BASELINE" ]; then
  echo "스냅샷이 없다: $BASELINE" >&2
  echo "최초 1회는 dev→main 승격(실배포) 직후 --update-baseline 으로 생성한 뒤 커밋할 것." >&2
  echo "기능 브랜치에서 뜨면 '직전 릴리스' 기준이 아니게 되므로 승격 시점에만 갱신한다." >&2
  exit 1
fi

echo "[1/2] live spec 수집: $BASE_URL/v3/api-docs"
curl -fsS "$BASE_URL/v3/api-docs" -o "$LIVE"

if [ "${1:-}" = "--update-baseline" ]; then
  # 릴리스 시점의 API 모습을 스냅샷으로 고정한다. 이후 이 파일이 "직전 릴리스" 기준이 되므로,
  # 갱신은 실제로 배포한 뒤에만 하고 반드시 커밋한다(갱신하지 않으면 다음 비교가 누적 변경을 본다).
  python -m json.tool "$LIVE" > "$BASELINE"
  echo "[2/2] 스냅샷 갱신 완료: $BASELINE"
  exit 0
fi

echo "[2/2] oasdiff 로 스냅샷 대비 변경 비교"
# ponytail: Git Bash(MSYS)의 자동 경로변환이 호스트/컨테이너 경로를 뒤섞는 걸 피하려고,
# 호스트 경로는 미리 cygpath로 윈도우 절대경로로 바꾸고, MSYS_NO_PATHCONV로 이후 변환을 전부 끈다
# (non-Windows/non-MSYS 환경에선 cygpath가 없으면 원본 경로를 그대로 씀 — 무해).
BASELINE_MOUNT="$BASELINE"
if command -v cygpath >/dev/null 2>&1; then
  BASELINE_MOUNT="$(cygpath -w "$BASELINE")"
  LIVE="$(cygpath -w "$LIVE")"
fi
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$BASELINE_MOUNT:/specs/baseline.json:ro" \
  -v "$LIVE:/specs/live.json:ro" \
  tufin/oasdiff breaking /specs/baseline.json /specs/live.json
