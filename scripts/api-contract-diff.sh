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

# 비교 범위에서 빼는 경로 — 계약서·코드 어느 쪽을 고쳐도 사라지지 않는(고치면 오히려 계약서가
# 부정확해지는) 것들만 넣는다. 실제 불일치를 여기 숨기지 말 것.
#   /ai/*, /health : ai-server(FastAPI) 담당. 비교 대상인 live spec 은 Spring 의 /v3/api-docs 라
#                    애초에 실릴 수 없다(Spring 은 프록시로 호출만 함).
#   /api/auth/oauth2/{provider} : Spring Security 의 OAuth2SuccessHandler 가 처리해 @Controller
#                    메서드가 아니다 — 실제로는 동작하지만 springdoc 이 문서화할 방법이 없다.
#   .../business-license/{storageKey} : 실제 라우팅이 capture-the-rest 라 live 경로 템플릿이
#                    {*storageKey} 로 나온다. 계약서는 OpenAPI 3.0 이 와일드카드 경로변수를 표현
#                    못해 의도적으로 {storageKey} 로 적었다(해당 경로 description 참조).
EXCLUDE_PATHS='^/(ai/|health$|api/auth/oauth2/|api/companies/[^/]+/business-license/)'

echo "[2/2] oasdiff로 필드명·타입·필수여부 비교"
# ponytail: Git Bash(MSYS)의 자동 경로변환이 호스트/컨테이너 경로를 뒤섞는 걸 피하려고,
# 호스트 경로는 미리 cygpath로 윈도우 절대경로로 바꾸고, MSYS_NO_PATHCONV로 이후 변환을 전부 끈다
# (non-Windows/non-MSYS 환경에선 cygpath가 없으면 원본 경로를 그대로 씀 — 무해).
if command -v cygpath >/dev/null 2>&1; then
  CONTRACT="$(cygpath -w "$CONTRACT")"
  LIVE="$(cygpath -w "$LIVE")"
fi
# --flatten-params: 계약서는 공통 path 파라미터를 path item 레벨에 두고 오퍼레이션이 상속하게 쓰는데
#   springdoc 은 오퍼레이션마다 개별로 쓴다. 이 옵션 없이는 표현 차이를 오asdiff 가 "파라미터가 새로
#   추가됐다"로 오판한다(실측 16건). 제외가 아니라 상속을 펼쳐서 제대로 비교하는 것이라, 가려졌던
#   진짜 불일치도 같이 드러난다(실측 +2건).
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$CONTRACT:/specs/contract.yaml:ro" \
  -v "$LIVE:/specs/live.json:ro" \
  tufin/oasdiff breaking /specs/contract.yaml /specs/live.json \
  --flatten-params \
  --unmatch-path "$EXCLUDE_PATHS"
