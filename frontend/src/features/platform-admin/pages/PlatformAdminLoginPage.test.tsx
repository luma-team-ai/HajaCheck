// @vitest-environment jsdom
// 플랫폼 관리자 로그인(#535) — Figma node 973-2520. 개인/기업 탭 없는 단일 아이디/비밀번호 폼이며,
// 로그인 자체는 성공해도 role이 PLATFORM_ADMIN이 아니면 세션을 무효화하고 에러만 노출해야 한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../../auth/store/authStore';
import type { EmailAvailabilityResponse, User } from '../../auth/types';
import { PlatformAdminLoginPage } from './PlatformAdminLoginPage';

const platformAdminUser: User = {
  id: 1,
  email: 'platform-admin@example.com',
  name: '플랫폼 운영진',
  role: 'PLATFORM_ADMIN',
  companyId: null,
  profileImageUrl: null,
};

const nonPlatformAdminUser: User = {
  ...platformAdminUser,
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
      <MemoryRouter initialEntries={['/platform-admin/login']}>
        <Routes>
          <Route
            path="/platform-admin/login"
            element={
              <>
                <PlatformAdminLoginPage />
                <LocationProbe />
              </>
            }
          />
          <Route path="/platform-admin" element={<LocationProbe />} />
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

function fillAndSubmit(loginId = 'admin@example.com', password = 'password1234') {
  fireEvent.change(screen.getByLabelText('아이디'), { target: { value: loginId } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
}

describe('PlatformAdminLoginPage', () => {
  it('아이디/비밀번호 단일 폼을 렌더한다(개인/기업 탭 없음)', () => {
    renderPage();

    expect(screen.getByText('관리자 로그인')).not.toBeNull();
    expect(screen.getByLabelText('아이디')).not.toBeNull();
    expect(screen.getByLabelText('비밀번호')).not.toBeNull();
    expect(screen.queryByRole('tablist')).toBeNull();
  });

  it('PLATFORM_ADMIN 계정으로 로그인 성공 시 authStore에 저장되고 /platform-admin으로 이동한다', async () => {
    mockLoginSuccess(platformAdminUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
    expect(useAuthStore.getState().user).toEqual(platformAdminUser);
    expect(logoutCallCount).toBe(0);
  });

  it('로그인은 성공하지만 role이 PLATFORM_ADMIN이 아니면 세션을 무효화하고 에러만 표시한다', async () => {
    mockLoginSuccess(nonPlatformAdminUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByText('플랫폼 관리자 계정이 아닙니다.')).not.toBeNull();
    });
    // 세션을 살려두지 않는다 — authStore에 커밋되지 않고, 서버 세션도 logout API로 무효화한다.
    expect(useAuthStore.getState().user).toBeNull();
    expect(logoutCallCount).toBe(1);
    // 페이지 이동 없이 로그인 화면에 그대로 머문다.
    expect(screen.getByTestId('location').textContent).toBe('/platform-admin/login');
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

  it('이미 PLATFORM_ADMIN으로 로그인된 상태로 진입하면 즉시 /platform-admin으로 이동한다', async () => {
    useAuthStore.setState({ user: platformAdminUser });
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
  });
});
