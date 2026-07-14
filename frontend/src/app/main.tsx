import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';
import { shouldEnableMocking } from './shouldEnableMocking';
import '../styles/global.css';

const queryClient = new QueryClient();

// worker.start() 가 (서비스워커 등록 지연·캐시 이슈 등으로) 영영 안 끝나면 앱이 흰 화면으로 멈추므로
// 타임아웃을 둔다 — 초과 시 목 없이 그냥 렌더한다(로컬 개발이라 목 미동작은 콘솔 경고로 족함).
const MSW_START_TIMEOUT_MS = 3000;

// 개발 모드에서만 MSW 목서버 구동 — 계약(#10) 확정 전까지 임시 mock으로 화면 개발.
// 로컬 실 백엔드 통합 확인 시엔 .env.local 에 VITE_ENABLE_MSW=false 로 끄면
// 모든 요청이 MSW를 거치지 않고 Vite proxy를 통해 실 백엔드로 나간다(기본값은 켜짐 — 기존 동작 유지).
async function enableMocking(): Promise<void> {
  if (!shouldEnableMocking(import.meta.env)) return;
  const { worker } = await import('../mocks/browser');
  await Promise.race([
    worker.start({ onUnhandledRequest: 'bypass' }),
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(`MSW worker.start 타임아웃(${MSW_START_TIMEOUT_MS}ms)`)), MSW_START_TIMEOUT_MS),
    ),
  ]);
}

function renderApp(): void {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </React.StrictMode>,
  );
}

// MSW 초기화 성공/실패/타임아웃과 무관하게 앱은 항상 렌더한다(목 실패로 흰 화면이 되지 않도록).
async function bootstrap(): Promise<void> {
  try {
    await enableMocking();
  } catch (error) {
    console.error('MSW 초기화 실패 — 목 없이 계속 진행합니다:', error);
  }
  renderApp();
}

void bootstrap();
