// @vitest-environment jsdom
// #846 — 로그인 실패 안내가 일반 <p>로 렌더돼 스크린리더가 실패 사유를 읽어주지 않던 문제.
// role="alert" 부여 후 getByRole('alert')로 실패 문구를 찾을 수 있는지 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { CompanyLoginTab } from './CompanyLoginTab';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderTab() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <CompanyLoginTab />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('CompanyLoginTab', () => {
  it('로그인 실패 시 role="alert"로 실패 문구를 찾을 수 있다', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'AUTH_INVALID_CREDENTIALS', message: '아이디 또는 비밀번호가 올바르지 않습니다.' },
        };
        return HttpResponse.json(failure, { status: 401 });
      }),
    );
    renderTab();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'hajacheck' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('아이디 또는 비밀번호가 올바르지 않습니다.'),
    );
  });

  // #1200 — CSRF 토큰 누락·만료로 로그인 POST가 403 FORBIDDEN으로 거부될 때, 매핑이 없어
  // 기본 문구("로그인에 실패했습니다")가 떠서 자격 증명 오류로 오인됐다. 재시도 유도 문구로 고정.
  it('403 FORBIDDEN(CSRF)은 재시도 안내 문구로 표시한다(#1200)', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FORBIDDEN', message: '접근 권한이 없습니다.' },
        };
        return HttpResponse.json(failure, { status: 403 });
      }),
    );
    renderTab();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'hajacheck' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'pw12345678' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('요청이 만료되었습니다. 다시 시도해 주세요.'),
    );
  });
});
