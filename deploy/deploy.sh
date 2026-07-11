#!/usr/bin/env bash
# HajaCheck 운영 배포 스크립트 — OCI VM에서 실행 (PRD §6.1)
# 사용: ./deploy/deploy.sh [IMAGE_TAG]
#   IMAGE_TAG 생략 시 배포일자+커밋 짧은 SHA로 자동 생성(latest 금지)
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE_TAG="${1:-$(date +%Y%m%d)-$(git rev-parse --short HEAD)}"
export IMAGE_TAG

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)

echo "==> git pull"
git pull --ff-only

echo "==> IMAGE_TAG=${IMAGE_TAG}"

echo "==> ${COMPOSE[*]} build"
"${COMPOSE[@]}" build

echo "==> ${COMPOSE[*]} up -d"
"${COMPOSE[@]}" up -d

echo "==> 헬스체크 폴링(최대 5분)"
SERVICES=(postgres redis spring fastapi nginx)
DEADLINE=$((SECONDS + 300))

for svc in "${SERVICES[@]}"; do
  cid=$("${COMPOSE[@]}" ps -q "$svc")
  if [ -z "$cid" ]; then
    echo "!! ${svc} 컨테이너를 찾을 수 없습니다"
    exit 1
  fi
  while true; do
    status=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid")
    if [ "$status" = "healthy" ] || [ "$status" = "no-healthcheck" ]; then
      echo "   ${svc}: ${status}"
      break
    fi
    if [ "$status" = "unhealthy" ]; then
      echo "!! ${svc} unhealthy — 로그 확인: docker compose -f docker-compose.yml -f docker-compose.prod.yml logs ${svc}"
      exit 1
    fi
    if [ "$SECONDS" -ge "$DEADLINE" ]; then
      echo "!! ${svc} 헬스체크 타임아웃(5분) — 로그 확인: docker compose -f docker-compose.yml -f docker-compose.prod.yml logs ${svc}"
      exit 1
    fi
    sleep 5
  done
done

echo "==> 배포 완료 (IMAGE_TAG=${IMAGE_TAG})"
