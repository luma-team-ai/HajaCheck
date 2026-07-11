import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Docker 로컬 개발(docker-compose.override.yml)에서는 서비스명(spring/fastapi)으로,
// 네이티브 로컬 개발(IDE 직접 실행)에서는 localhost로 프록시 — 환경변수로 전환
const springTarget = process.env.VITE_PROXY_SPRING ?? 'http://localhost:8080';
const fastapiTarget = process.env.VITE_PROXY_FASTAPI ?? 'http://localhost:8000';

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    proxy: {
      '/api': springTarget,
      '/ws': { target: springTarget, ws: true },
      // ai-server의 헬스체크는 prefix 없는 /health — nginx(frontend/nginx/default.conf)와 동일하게
      // /ai/health만 리라이트하고 나머지 /ai/*(ai_router prefix)는 그대로 전달
      '/ai': {
        target: fastapiTarget,
        rewrite: (path) => (path === '/ai/health' ? '/health' : path),
      },
    },
  },
});
