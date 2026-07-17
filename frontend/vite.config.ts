import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Docker 로컬 개발(docker-compose.override.yml)에서는 서비스명(spring/fastapi)으로,
// 네이티브 로컬 개발(IDE 직접 실행)에서는 localhost로 프록시 — 환경변수로 전환
const springTarget = process.env.VITE_PROXY_SPRING ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: true,
    proxy: {
      // AI 호출(aiClient baseURL=/api/ai)도 스프링 인증 프록시를 경유 — dev에서도 보안 경계 일관
      '/api': springTarget,
      '/ws': { target: springTarget, ws: true },
    },
  },
});
