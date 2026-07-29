// @vitest-environment jsdom
// 상담원 전용 로그인 — PlatformAdminLoginPage.test.tsx와 동일 커버리지(#535 패턴). 개인/기업 탭
// 없는 단일 아이디/비밀번호 폼이며, 로그인 자체는 성공해도 role이 COUNSELOR가 아니면 세션을
// 무효화하고 에러만 노출해야 한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../../auth/store/authStore';
import type { EmailAvailabilityResponse, User } from '../../auth/types';
import { CounselorLoginPage } from './CounselorLoginPage';

const counselorUser: User = {
  id: 1,
  email: 'counselor@example.com',
  name: '김상담',
  role: 'COUNSELOR',
  companyId: null,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: null,
  status: 'ACTIVE',
};

const nonCounselorUser: User = {
  ...counselorUser,
  id: 2,
  role: 'USER',
  companyId: 1,
};

let logoutCallCount = 0;

const csrfPrimeHandler = http.get('/api/auth/email-availability', () => {
  const success: ApiResponse<EmailAvailabilityResponse> = { success: true, data: { available: true } };
  return HttpResponse.json(success);
});
const logoutHandler = http.post('/api/auth/logout', () => {
  logoutCallCount += 1;
  const success: ApiResponse<null> = { success: true, data: null };
  return HttpResponse.json(success);
});

const server = setupServer(csrfPrimeHandler, logoutHandler);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
  logoutCallCount = 0;
});
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/counsel-console/login']}>
        <Routes>
          <Route
            path="/counsel-console/login"
            element={
              <>
                <CounselorLoginPage />
                <LocationProbe />
              </>
            }
          />
          <Route path="/counsel-console/queue" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockLoginSuccess(user: User) {
  server.use(
    http.post('/api/auth/login', () => {
      const success: ApiResponse<User> = { success: true, data: user };
      return HttpResponse.json(success);
    }),
  );
}

function fillAndSubmit(loginId = 'counselor@example.com', password = 'password1234') {
  fireEvent.change(screen.getByLabelText('아이디'), { target: { value: loginId } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
}

describe('CounselorLoginPage', () => {
  it('아이디/비밀번호 단일 폼을 렌더한다(개인/기업 탭 없음)', () => {
    renderPage();

    expect(screen.getByText('상담원 로그인')).not.toBeNull();
    expect(screen.getByLabelText('아이디')).not.toBeNull();
    expect(screen.getByLabelText('비밀번호')).not.toBeNull();
    expect(screen.queryByRole('tablist')).toBeNull();
  });

  it('COUNSELOR 계정으로 로그인 성공 시 authStore에 저장되고 /counsel-console/queue로 이동한다', async () => {
    mockLoginSuccess(counselorUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/counsel-console/queue');
    });
    expect(useAuthStore.getState().user).toEqual(counselorUser);
    expect(logoutCallCount).toBe(0);
  });

  it('로그인은 성공하지만 role이 COUNSELOR가 아니면 세션을 무효화하고 에러만 표시한다', async () => {
    mockLoginSuccess(nonCounselorUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByText('상담원 계정이 아닙니다.')).not.toBeNull();
    });
    // 세션을 살려두지 않는다 — authStore에 커밋되지 않고, 서버 세션도 logout API로 무효화한다.
    expect(useAuthStore.getState().user).toBeNull();
    expect(logoutCallCount).toBe(1);
    // 페이지 이동 없이 로그인 화면에 그대로 머문다.
    expect(screen.getByTestId('location').textContent).toBe('/counsel-console/login');
  });

  // PR머신 리뷰 P3(#558) — role 불일치 시 서버 세션 무효화(logout) 실패를 조용히 삼키면 안 된다.
  it('role 불일치 처리 중 logout API가 실패해도 authStore는 정리되고, 실패가 별도 메시지로 안내된다', async () => {
    mockLoginSuccess(nonCounselorUser);
    server.use(
      http.post('/api/auth/logout', () => HttpResponse.json({ success: false }, { status: 500 })),
    );
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    try {
      renderPage();
      fillAndSubmit();

      await waitFor(() => {
        expect(
          screen.getByText(
            '상담원 계정이 아닙니다. 세션 정리에 실패했으니 브라우저를 종료한 뒤 다시 시도해 주세요.',
          ),
        ).not.toBeNull();
      });
      expect(useAuthStore.getState().user).toBeNull();
      expect(errorSpy).toHaveBeenCalled();
    } finally {
      errorSpy.mockRestore();
    }
  });

  it('잘못된 자격증명(401)이면 안내 메시지를 표시한다', async () => {
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
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByText('아이디 또는 비밀번호가 올바르지 않습니다.')).not.toBeNull();
    });
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('이미 COUNSELOR로 로그인된 상태로 진입하면 즉시 /counsel-console/queue로 이동한다', async () => {
    useAuthStore.setState({ user: counselorUser });
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/counsel-console/queue');
    });
  });
});
