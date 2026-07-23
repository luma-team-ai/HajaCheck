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
    watch: {
      // Docker Desktop(Windows) 바인드 마운트는 네이티브 파일시스템 이벤트가 컨테이너로
      // 전파되지 않아 기본 chokidar 감시로는 HMR이 소스 변경을 못 잡는다(수정해도 이전 코드가
      // 계속 서빙됨) — docker-compose.override.yml이 frontend-dev에만 VITE_WATCH_POLL=true를
      // 주입해 폴링으로 강제 감지한다. 네이티브(IDE 직접 실행) 로컬 개발은 기본 off로 유지해
      // 불필요한 CPU 폴링 비용을 피한다.
      usePolling: process.env.VITE_WATCH_POLL === 'true',
    },
    proxy: {
      // AI 호출(aiClient baseURL=/api/ai)도 스프링 인증 프록시를 경유 — dev에서도 보안 경계 일관
      '/api': springTarget,
      '/ws': { target: springTarget, ws: true },
    },
  },
});
