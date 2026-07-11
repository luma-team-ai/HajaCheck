#!/usr/bin/env bash
# HajaCheck 운영 배포 스크립트 — OCI VM에서 실행 (PRD §6.1)
# 사용: ./deploy/deploy.sh [IMAGE_TAG]
#   IMAGE_TAG 생략 시 배포일자+커밋 짧은 SHA로 자동 생성(latest 금지)
# 롤백: 전 서비스 healthy로 배포 성공한 경우에만 .last_good_tag(배포 디렉토리 내, 서버 로컬
#   파일)에 IMAGE_TAG를 기록한다. 이후 배포가 헬스체크 unhealthy/타임아웃으로 실패하면
#   .last_good_tag가 있을 때 그 태그로 즉시 재기동(자동 롤백) 후 exit 1, 없으면(최초 배포)
#   롤백 없이 exit 1.
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE_TAG="${1:-$(date +%Y%m%d)-$(git rev-parse --short HEAD)}"
export IMAGE_TAG

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
LAST_GOOD_TAG_FILE=".last_good_tag"
SERVICE_TIMEOUT=300

echo "==> git pull"
git pull --ff-only

echo "==> IMAGE_TAG=${IMAGE_TAG}"

echo "==> ${COMPOSE[*]} build"
"${COMPOSE[@]}" build

echo "==> ${COMPOSE[*]} up -d"
"${COMPOSE[@]}" up -d

rollback() {
  local reason="$1"
  echo "!! ${reason}"
  if [ -f "$LAST_GOOD_TAG_FILE" ]; then
    local last_good
    last_good=$(cat "$LAST_GOOD_TAG_FILE")
    echo "==> 자동 롤백: IMAGE_TAG=${last_good}로 재기동"
    IMAGE_TAG="$last_good" "${COMPOSE[@]}" up -d
    echo "!! 배포 실패 — IMAGE_TAG=${last_good}(직전 정상 배포)로 롤백 완료. 원인 확인: docker compose -f docker-compose.yml -f docker-compose.prod.yml logs"
  else
    echo "!! 배포 실패 — ${LAST_GOOD_TAG_FILE} 없음(최초 배포로 추정) — 롤백 스킵"
  fi
  exit 1
}

echo "==> 헬스체크 폴링(서비스당 최대 ${SERVICE_TIMEOUT}s)"
SERVICES=(postgres redis spring fastapi nginx)

for svc in "${SERVICES[@]}"; do
  cid=$("${COMPOSE[@]}" ps -q "$svc")
  if [ -z "$cid" ]; then
    rollback "${svc} 컨테이너를 찾을 수 없습니다"
  fi
  DEADLINE=$((SECONDS + SERVICE_TIMEOUT))
  while true; do
    status=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid")
    if [ "$status" = "healthy" ] || [ "$status" = "no-healthcheck" ]; then
      echo "   ${svc}: ${status}"
      break
    fi
    if [ "$status" = "unhealthy" ]; then
      rollback "${svc} unhealthy — 로그 확인: docker compose -f docker-compose.yml -f docker-compose.prod.yml logs ${svc}"
    fi
    if [ "$SECONDS" -ge "$DEADLINE" ]; then
      rollback "${svc} 헬스체크 타임아웃(${SERVICE_TIMEOUT}s) — 로그 확인: docker compose -f docker-compose.yml -f docker-compose.prod.yml logs ${svc}"
    fi
    sleep 5
  done
done

echo "${IMAGE_TAG}" > "$LAST_GOOD_TAG_FILE"
echo "==> 배포 완료 (IMAGE_TAG=${IMAGE_TAG})"
