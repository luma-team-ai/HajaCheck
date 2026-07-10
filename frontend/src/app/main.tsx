import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';
import '../styles/global.css';

const queryClient = new QueryClient();

// 개발 모드에서만 MSW 목서버 구동 — 계약(#10) 확정 전까지 임시 mock으로 화면 개발
async function enableMocking() {
  if (!import.meta.env.DEV) return;
  const { worker } = await import('../mocks/browser');
  return worker.start({ onUnhandledRequest: 'bypass' });
}

enableMocking()
  .then(() => {
    ReactDOM.createRoot(document.getElementById('root')!).render(
      <React.StrictMode>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </React.StrictMode>,
    );
  })
  .catch((error) => {
    console.error('MSW 초기화 실패:', error);
    const root = document.getElementById('root');
    if (root) {
      root.innerHTML = '<div style="padding: 20px; color: red;">개발 서버 오류: Mock 서버를 시작할 수 없습니다. 콘솔을 확인하세요.</div>';
    }
  });
