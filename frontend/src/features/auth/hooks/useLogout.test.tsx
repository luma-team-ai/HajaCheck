// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { getRagSessionId, setRagSessionId } from '../../support/utils/ragSessionId';
import { AUTH_ME_QUERY_KEY } from '../constants';
import { useAuthStore } from '../store/authStore';
import type { User } from '../types';
import { useLogout } from './useLogout';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '하자체크',
  status: 'ACTIVE',
};

let logoutCallCount = 0;

const handlers = [
  http.post('/api/auth/logout', () => {
    logoutCallCount += 1;
    const success: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(success);
  }),
];

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
});
afterAll(() => server.close());

const waitFor = (predicate: () => boolean, timeout = 3000): Promise<void> => {
  return new Promise((resolve, reject) => {
    const startTime = Date.now();
    const interval = setInterval(() => {
      if (predicate()) {
        clearInterval(interval);
        resolve();
      } else if (Date.now() - startTime > timeout) {
        clearInterval(interval);
        reject(new Error('Timeout waiting for condition'));
      }
    }, 20);
  });
};

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function LogoutButton({ redirectTo }: { redirectTo?: string }) {
  const { logout } = useLogout(redirectTo);
  return (
    <button type="button" onClick={() => void logout()}>
      로그아웃
    </button>
  );
}

function renderWithProviders(queryClient: QueryClient, redirectTo?: string) {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <LogoutButton redirectTo={redirectTo} />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('useLogout', () => {
  beforeEach(() => {
    logoutCallCount = 0;
    useAuthStore.setState({ user: mockUser });
    setRagSessionId(77);
  });

  // #1590 — RAG 챗봇 session_id(localStorage 영속)를 로그아웃에서 지우지 않으면 공용 PC에서
  // 다음 사용자의 첫 질의가 이전 사용자 세션으로 나가 403으로 실패한다.
  it('로그아웃 시 RAG session_id도 정리한다', async () => {
    const queryClient = new QueryClient();
    renderWithProviders(queryClient);

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => screen.getByTestId('location').textContent === '/login');

    expect(getRagSessionId()).toBeNull();
  });

  it('logout API가 실패해도 RAG session_id는 정리된다', async () => {
    server.use(http.post('/api/auth/logout', () => HttpResponse.error()));
    const queryClient = new QueryClient();
    renderWithProviders(queryClient);

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => screen.getByTestId('location').textContent === '/login');

    expect(getRagSessionId()).toBeNull();
  });

  it('로그아웃 성공 시 API 호출 + 캐시/스토어 정리 + /login으로 이동한다', async () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(['probe'], 'cached-value');
    renderWithProviders(queryClient);

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => screen.getByTestId('location').textContent === '/login');

    expect(logoutCallCount).toBe(1);
    expect(queryClient.getQueryData(['probe'])).toBeUndefined();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('logout API가 실패해도 클라이언트 세션은 정리되고 /login으로 이동한다', async () => {
    server.use(http.post('/api/auth/logout', () => HttpResponse.error()));

    const queryClient = new QueryClient();
    queryClient.setQueryData(['probe'], 'cached-value');
    renderWithProviders(queryClient);

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => screen.getByTestId('location').textContent === '/login');

    expect(queryClient.getQueryData(['probe'])).toBeUndefined();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('cancelQueries(auth me)가 setQueryData(null)보다 먼저 호출된다(in-flight 재복원 레이스 방지, #280 P3)', async () => {
    const queryClient = new QueryClient();
    const callOrder: string[] = [];
    const cancelQueriesSpy = vi
      .spyOn(queryClient, 'cancelQueries')
      .mockImplementation(async () => {
        callOrder.push('cancelQueries');
        return undefined;
      });
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData').mockImplementation(() => {
      callOrder.push('setQueryData');
      return undefined;
    });

    renderWithProviders(queryClient);

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => screen.getByTestId('location').textContent === '/login');

    expect(cancelQueriesSpy).toHaveBeenCalledWith({ queryKey: AUTH_ME_QUERY_KEY });
    expect(setQueryDataSpy).toHaveBeenCalledWith(AUTH_ME_QUERY_KEY, null);
    expect(callOrder.indexOf('cancelQueries')).toBeLessThan(callOrder.indexOf('setQueryData'));
  });

  // redirectTo(#535) — 플랫폼 관리자 콘솔은 로그아웃 후 /login이 아니라 /platform-admin/login으로 돌아가야 한다.
  it('redirectTo를 지정하면 그 경로로 이동한다(#535 플랫폼 관리자 콘솔)', async () => {
    const queryClient = new QueryClient();
    renderWithProviders(queryClient, '/platform-admin/login');

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => screen.getByTestId('location').textContent === '/platform-admin/login');

    expect(useAuthStore.getState().user).toBeNull();
  });
});
