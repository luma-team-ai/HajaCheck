# syntax=docker/dockerfile:1
# HajaCheck frontend — 멀티스테이지 빌드: node(Vite 빌드) → nginx(정적 서빙 + 리버스 프록시)
# 버전은 package.json(Vite 6 / React 18)을 그대로 따른다. 기본 outDir(dist) 사용.

# ---- Build stage ----
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Runtime stage (nginx: React dist 정적 서빙 + /api·/ws·/ai 리버스 프록시) ----
FROM nginx:1.27-alpine AS runtime

# 배포 대상별 nginx conf 선택(기본=전용 VM용 default.conf, 공유 호스트 arm1은
# `--build-arg NGINX_CONF_FILE=nginx/arm1.conf`로 docker-compose.arm1.yml이 주입 — 이슈 #104)
ARG NGINX_CONF_FILE=nginx/default.conf

COPY --from=build /app/dist /usr/share/nginx/html
COPY ${NGINX_CONF_FILE} /etc/nginx/conf.d/default.conf

# nginx 공식 이미지 표준 관행: master는 80/443 저포트 바인딩을 위해 root로 시작하고,
# 실제 요청을 처리하는 worker 프로세스는 nginx.conf 기본값 `user nginx;`로 non-root 실행됨.
# TLS(443, 운영)는 이 PR 범위 밖(서버 커토버·certbot 발급 미포함) — nginx/tls.conf.example 참고.
EXPOSE 80 443

# 127.0.0.1 명시 — "localhost"가 컨테이너 내부에서 IPv6(::1)로 먼저 풀리면 wget이 폴백 없이
# connection refused로 실패함(nginx는 IPv4만 리슨, listen [::]는 stock 템플릿에만 자동 추가됨)
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=6 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
