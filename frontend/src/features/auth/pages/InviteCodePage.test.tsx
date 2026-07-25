// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../store/authStore';
import type { User } from '../types';
import { InviteCodePage } from './InviteCodePage';

const waitingUser: User = {
  id: 1,
  email: 'waiting@example.com',
  name: '초대 대기 사용자',
  role: 'USER',
  companyId: null,
  profileImageUrl: null,
  createdAt: '2026-07-25T00:00:00',
  companyName: null,
  status: 'WAITING',
};

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
});
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({ user: waitingUser });
});

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/invite-code']}>
        <Routes>
          <Route path="/invite-code" element={<InviteCodePage />} />
          <Route path="/dashboard" element={<div>대시보드 화면</div>} />
          <Route path="/login" element={<div>로그인 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function typeCode(code: string) {
  const inputs = screen.getAllByLabelText(/초대 코드 \d번째 자리/);
  code.split('').forEach((char, index) => {
    fireEvent.change(inputs[index], { target: { value: char } });
  });
}

describe('InviteCodePage', () => {
  it('6자리를 모두 채우지 않고 확인을 누르면 로컬 에러를 보여준다', () => {
    renderPage();
    typeCode('AB12');

    fireEvent.click(screen.getByRole('button', { name: /확인/ }));

    expect(screen.getByRole('alert').textContent).toContain('6자리 초대 코드를 모두 입력해 주세요.');
  });

  it('redeem 성공 시 authStore.user를 갱신하고 대시보드로 이동한다', async () => {
    server.use(
      http.post('/api/users/me/invite-code', () => {
        const success: ApiResponse<User> = {
          success: true,
          data: { ...waitingUser, companyId: 7, companyName: '테스트회사', status: 'ACTIVE' },
        };
        return HttpResponse.json(success);
      }),
    );
    renderPage();
    typeCode('ABC123');

    fireEvent.click(screen.getByRole('button', { name: /확인/ }));

    await waitFor(() => expect(screen.getByText('대시보드 화면')).not.toBeNull());
    expect(useAuthStore.getState().user?.status).toBe('ACTIVE');
    expect(useAuthStore.getState().user?.companyId).toBe(7);
  });

  it('유효하지 않은 코드면 서버 에러 메시지를 보여주고 화면에 머문다', async () => {
    server.use(
      http.post('/api/users/me/invite-code', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'AUTH_INVITE_CODE_INVALID', message: '유효하지 않거나 만료된 초대 코드입니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );
    renderPage();
    typeCode('ABC123');

    fireEvent.click(screen.getByRole('button', { name: /확인/ }));

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('유효하지 않거나 만료된 초대 코드입니다.'),
    );
    expect(screen.queryByText('대시보드 화면')).toBeNull();
  });

  // #817 P3 — 확인 버튼을 마우스로 누르지 않아도 6자리 입력 후 Enter로 제출 가능해야 한다.
  it('6자리 입력 후 Enter를 누르면 redeem이 호출된다', async () => {
    server.use(
      http.post('/api/users/me/invite-code', () => {
        const success: ApiResponse<User> = {
          success: true,
          data: { ...waitingUser, companyId: 7, companyName: '테스트회사', status: 'ACTIVE' },
        };
        return HttpResponse.json(success);
      }),
    );
    renderPage();
    typeCode('ABC123');

    const inputs = screen.getAllByLabelText(/초대 코드 \d번째 자리/);
    fireEvent.submit(inputs[5].closest('form') as HTMLFormElement);

    await waitFor(() => expect(screen.getByText('대시보드 화면')).not.toBeNull());
  });

  it('이미 회사에 연결된(WAITING 아님) 사용자가 접근하면 대시보드로 리다이렉트한다', () => {
    useAuthStore.setState({ user: { ...waitingUser, status: 'ACTIVE', companyId: 7 } });

    renderPage();

    expect(screen.getByText('대시보드 화면')).not.toBeNull();
  });

  // "취소"는 랜딩으로만 이동할 뿐 로그아웃하지 않아, 같은 WAITING 세션으로는 다시 로그인해도
  // 이 화면으로 돌아온다 — 다른 계정으로 시도하려면 명시적 로그아웃이 필요하다(사용자 피드백).
  it('"다른 계정으로 로그인"을 누르면 로그아웃 후 로그인 화면으로 이동한다', async () => {
    server.use(http.post('/api/auth/logout', () => HttpResponse.json({ success: true, data: null })));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '다른 계정으로 로그인' }));

    await waitFor(() => expect(screen.getByText('로그인 화면')).not.toBeNull());
    expect(useAuthStore.getState().user).toBeNull();
  });
});
