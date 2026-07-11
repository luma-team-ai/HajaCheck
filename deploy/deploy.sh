#!/usr/bin/env bash
# HajaCheck 운영 배포 스크립트 — OCI VM에서 실행 (PRD §6.1)
# 사용: ./deploy/deploy.sh [IMAGE_TAG]
#   IMAGE_TAG 생략 시 배포일자+커밋 짧은 SHA로 자동 생성(latest 금지) — arm1 대상은 미사용(build만).
# 배포 대상(DEPLOY_TARGET, 서버 로컬 .env에서 **명시 필수** — 이 스크립트에 하드코딩 금지, 이슈 #104):
#   vm    — 전용 VM: docker-compose.yml + docker-compose.prod.yml (postgres/redis/nginx 포함 풀스택)
#   arm1  — 공유 호스트(oci-arm1): docker-compose.arm1.yml만(standalone, host-net 앱 3개,
#           공유 nginx/postgres/redis는 이 스크립트가 건드리지 않음)
# DEPLOY_TARGET이 .env에 없거나 vm/arm1이 아니면 즉시 exit 1(조용한 vm 폴백 금지 — 공유 호스트에서
# 실수로 풀스택 vm 경로가 돌면 80/443·postgres·redis가 충돌해 다른 사이트가 죽는다. 리뷰 P1).
# 롤백(vm 대상만): 전 서비스 healthy로 배포 성공한 경우에만 .last_good_tag(배포 디렉토리 내, 서버 로컬
#   파일)에 IMAGE_TAG를 기록한다. 이후 배포가 헬스체크 unhealthy/타임아웃으로 실패하면
#   .last_good_tag가 있을 때 그 태그로 즉시 재기동(자동 롤백) 후 exit 1, 없으면(최초 배포)
#   롤백 없이 exit 1. arm1은 이미지 태그 버전관리를 쓰지 않으므로 롤백 스킵(로그 확인 후 수동 대응).
set -euo pipefail

cd "$(dirname "$0")/.."

DEPLOY_TARGET=""
if [ -f .env ]; then
  DEPLOY_TARGET=$(grep -E '^DEPLOY_TARGET=' .env | tail -n1 | cut -d'=' -f2- | tr -d "[:space:]\"'" || true)
fi

case "$DEPLOY_TARGET" in
  vm|arm1) ;;
  *)
    echo "!! DEPLOY_TARGET='${DEPLOY_TARGET}' 유효하지 않음 — .env에 vm 또는 arm1로 명시해야 합니다"
    exit 1
    ;;
esac

IMAGE_TAG="${1:-$(date +%Y%m%d)-$(git rev-parse --short HEAD)}"
export IMAGE_TAG

if [ "$DEPLOY_TARGET" = "arm1" ]; then
  COMPOSE=(docker compose -f docker-compose.arm1.yml)
  SERVICES=(spring fastapi frontend)
else
  COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
  SERVICES=(postgres redis spring fastapi nginx)
fi

LAST_GOOD_TAG_FILE=".last_good_tag"
SERVICE_TIMEOUT=300

echo "==> git pull"
git pull --ff-only

echo "==> DEPLOY_TARGET=${DEPLOY_TARGET} IMAGE_TAG=${IMAGE_TAG}"

echo "==> ${COMPOSE[*]} build"
"${COMPOSE[@]}" build

echo "==> ${COMPOSE[*]} up -d"
"${COMPOSE[@]}" up -d

rollback() {
  local reason="$1"
  echo "!! ${reason}"
  if [ "$DEPLOY_TARGET" != "arm1" ] && [ -f "$LAST_GOOD_TAG_FILE" ]; then
    local last_good
    last_good=$(cat "$LAST_GOOD_TAG_FILE")
    echo "==> 자동 롤백: IMAGE_TAG=${last_good}로 재기동"
    IMAGE_TAG="$last_good" "${COMPOSE[@]}" up -d
    echo "!! 배포 실패 — IMAGE_TAG=${last_good}(직전 정상 배포)로 롤백 완료. 원인 확인: ${COMPOSE[*]} logs"
  elif [ "$DEPLOY_TARGET" = "arm1" ]; then
    echo "!! 배포 실패 — arm1 대상은 자동 롤백 미지원(IMAGE_TAG 버전관리 미사용). 로그 확인: ${COMPOSE[*]} logs"
  else
    echo "!! 배포 실패 — ${LAST_GOOD_TAG_FILE} 없음(최초 배포로 추정) — 롤백 스킵"
  fi
  exit 1
}

echo "==> 헬스체크 폴링(서비스당 최대 ${SERVICE_TIMEOUT}s)"

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
      rollback "${svc} unhealthy — 로그 확인: ${COMPOSE[*]} logs ${svc}"
    fi
    if [ "$SECONDS" -ge "$DEADLINE" ]; then
      rollback "${svc} 헬스체크 타임아웃(${SERVICE_TIMEOUT}s) — 로그 확인: ${COMPOSE[*]} logs ${svc}"
    fi
    sleep 5
  done
done

if [ "$DEPLOY_TARGET" != "arm1" ]; then
  echo "${IMAGE_TAG}" > "$LAST_GOOD_TAG_FILE"
fi
echo "==> 배포 완료 (DEPLOY_TARGET=${DEPLOY_TARGET}, IMAGE_TAG=${IMAGE_TAG})"
