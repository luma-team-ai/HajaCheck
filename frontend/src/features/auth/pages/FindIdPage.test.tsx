// @vitest-environment jsdom
// 회귀 테스트(#301, HAJA-224 후속) — 비밀번호 찾기가 이메일 링크 방식으로 구현되면서
// FindIdPage의 "비밀번호 찾기" 링크도 AuthFooterLinks와 동일하게 실제 라우트로 이동해야 한다.
// 이전에는 "준비 중인 기능입니다" alert만 띄웠는데, 로그인 화면에서는 이미 동작하는 기능이라
// 화면마다 다르게 동작하는 회귀가 있었다(메타 검수 지적).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { EmailAvailabilityResponse } from '../types';
import { FindIdPage } from './FindIdPage';

// useCsrfPrime이 마운트 시 호출하는 GET(더미 이메일 중복확인) — 에러만 안 나면 됨
const csrfPrimeHandler = http.get('/api/auth/email-availability', () => {
  const success: ApiResponse<EmailAvailabilityResponse> = { success: true, data: { available: true } };
  return HttpResponse.json(success);
});

const server = setupServer(csrfPrimeHandler);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/find-id']}>
        <Routes>
          <Route path="/find-id" element={<FindIdPage />} />
          <Route path="/find-password" element={<div>비밀번호 찾기 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FindIdPage — 비밀번호 찾기 링크', () => {
  it('"비밀번호 찾기" 클릭 시 /find-password로 이동한다(alert 아님)', () => {
    renderPage();

    fireEvent.click(screen.getByRole('link', { name: '비밀번호 찾기' }));

    expect(screen.getByText('비밀번호 찾기 화면')).not.toBeNull();
  });
});
