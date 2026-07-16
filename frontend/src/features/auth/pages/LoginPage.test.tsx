// @vitest-environment jsdom
// #280 P2·P3 후속 하드닝 테스트 — LoginPage 세션체크 refetch 억제 + state.from 오픈 리다이렉트 검증.
import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../store/authStore';
import type { User } from '../types';
import { LoginPage } from './LoginPage';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
};

let getMeCallCount = 0;

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
  // focusManager는 process 전역 싱글턴이라 테스트 간 오염을 막기 위해 자동판정으로 되돌린다.
  focusManager.setFocused(undefined);
});
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

type InitialEntry = { pathname: string; state?: unknown };

function renderLoginPage(queryClient: QueryClient, initialEntry: InitialEntry) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/login" element={<LoginPageWithProbe />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function LoginPageWithProbe() {
  return (
    <>
      <LoginPage />
      <LocationProbe />
    </>
  );
}

function mockGetMeSuccess() {
  server.use(
    http.get('/api/users/me', () => {
      getMeCallCount += 1;
      const success: ApiResponse<User> = { success: true, data: mockUser };
      return HttpResponse.json(success);
    }),
  );
}

function mockGetMeUnauthorized() {
  server.use(
    http.get('/api/users/me', () => {
      getMeCallCount += 1;
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' },
      };
      return HttpResponse.json(failure, { status: 401 });
    }),
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    getMeCallCount = 0;
  });

  it('세션 있음 + state.from이 안전한 내부 경로면 그 경로로 이동한다', async () => {
    mockGetMeSuccess();
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login', state: { from: '/defects/1' } });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/defects/1');
    });
  });

  it('세션 있음 + state.from이 오픈 리다이렉트 시도(//evil.com)면 /dashboard로 폴백한다(#280 P3)', async () => {
    mockGetMeSuccess();
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login', state: { from: '//evil.com' } });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/dashboard');
    });
  });

  it('세션 있음 + state.from 없으면 /dashboard로 이동한다', async () => {
    mockGetMeSuccess();
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/dashboard');
    });
  });

  it('탭 포커스가 돌아와도 getMe를 재요청하지 않는다(refetchOnWindowFocus:false, #280 P2)', async () => {
    mockGetMeUnauthorized();
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(getMeCallCount).toBe(1);
    });
    // 401은 정상 흐름(미로그인)이라 로그인 폼이 그대로 노출됨을 확인
    await waitFor(() => {
      expect(screen.getByRole('tablist')).not.toBeNull();
    });

    focusManager.setFocused(false);
    focusManager.setFocused(true);

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(getMeCallCount).toBe(1);
  });
});
