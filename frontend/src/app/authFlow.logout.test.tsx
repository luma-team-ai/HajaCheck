// @vitest-environment jsdom
// PR #232 3차 재검수 회귀 테스트를 새 앱 셸(AppShellRoute+AppLayout, #227/HAJA-186)에 맞게 이식(#231, HAJA-189).
// AuthGate + ProtectedRoute + AppShellRoute(SideNavBar 로그아웃 버튼) + LoginPage를 실제 컴포넌트로
// 조합한 통합 테스트. 근본원인: 로그아웃의 queryClient.clear()가 AuthGate의 상시 getMe 옵저버를
// 재-pending시켜 스플래시가 재노출되고(P2-C), 그 재구독이 즉시 재요청으로 이어져 쿠키가 아직
// 유효하면 세션이 재복원되던 문제(P2-D)를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { useAuthStore } from '../features/auth/store/authStore';
import type { User } from '../features/auth/types';
import { ProtectedRoute } from '../shared/components/ProtectedRoute';
import type { ApiResponse } from '../shared/api/types';
import { AppShellRoute } from './AppShellRoute';
import { AuthGate } from './AuthGate';

const mockUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '하자체크',
};

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
});
afterAll(() => server.close());

// useMatches()(AppShellRoute)는 data router에서만 동작하므로 실제 router.tsx와 동일하게
// createMemoryRouter/RouterProvider로 구성한다 — ProtectedRoute가 AppShell 부모를 감싸고,
// /dashboard가 그 자식(handle로 breadcrumb 선언)이라 SideNavBar 로그아웃 버튼까지 실제로 렌더된다.
function renderApp(initialPath = '/dashboard') {
  const queryClient = new QueryClient();
  const router = createMemoryRouter(
    [
      {
        element: (
          <ProtectedRoute>
            <AppShellRoute />
          </ProtectedRoute>
        ),
        children: [
          {
            path: '/dashboard',
            element: <div>대시보드 콘텐츠</div>,
            handle: { breadcrumb: [{ label: '홈' }, { label: '대시보드' }], activeHref: '/dashboard' },
          },
        ],
      },
      { path: '/login', element: <LoginPage /> },
    ],
    { initialEntries: [initialPath] },
  );

  return render(
    <QueryClientProvider client={queryClient}>
      <AuthGate>
        <RouterProvider router={router} />
      </AuthGate>
    </QueryClientProvider>,
  );
}

describe('로그아웃 흐름(AuthGate + ProtectedRoute + AppShellRoute + LoginPage 통합, 새 셸)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
  });

  it('정상 로그아웃(getMe 401) 시 스플래시 재노출 없이 /login에 도달한다(P2-C)', async () => {
    let getMeCallCount = 0;
    server.use(
      http.get('/api/users/me', () => {
        getMeCallCount += 1;
        if (getMeCallCount === 1) {
          const success: ApiResponse<User> = { success: true, data: mockUser };
          return HttpResponse.json(success);
        }
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' },
        };
        return HttpResponse.json(failure, { status: 401 });
      }),
      http.post('/api/auth/logout', () => {
        const success: ApiResponse<null> = { success: true, data: null };
        return HttpResponse.json(success);
      }),
    );

    renderApp();

    // 부트스트랩 완료 — 대시보드 콘텐츠가 보이고 스플래시는 사라진 상태
    await waitFor(() => {
      expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    });
    expect(screen.queryByRole('status')).toBeNull();

    fireEvent.click(screen.getByText('로그아웃'));

    // 로그아웃 처리 도중에도 스플래시(부트스트랩 로딩)가 재노출되지 않아야 한다
    expect(screen.queryByRole('status')).toBeNull();

    await waitFor(() => {
      expect(screen.getByRole('tablist')).not.toBeNull(); // LoginPage 도달 확인
    });

    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('로그아웃 API 실패 + 쿠키 유효(getMe 200)여도 세션이 재복원되지 않고 /login을 유지한다(P2-D)', async () => {
    server.use(
      http.get('/api/users/me', () => {
        // 로그아웃 API가 실패해 서버 세션(쿠키)이 여전히 유효한 상황을 시뮬레이션
        const success: ApiResponse<User> = { success: true, data: mockUser };
        return HttpResponse.json(success);
      }),
      http.post('/api/auth/logout', () => HttpResponse.error()),
    );

    renderApp();

    await waitFor(() => {
      expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    });

    fireEvent.click(screen.getByText('로그아웃'));

    await waitFor(() => {
      expect(screen.getByRole('tablist')).not.toBeNull(); // LoginPage 도달 확인
    });

    // 쿠키가 유효해 getMe가 200을 반환하더라도, 공유 staleTime 덕분에 LoginPage가
    // 즉시 재요청하지 않아 세션이 재복원되지 않아야 한다 — 일정 시간 대기 후에도 유지 확인
    await new Promise((resolve) => setTimeout(resolve, 200));

    expect(screen.getByRole('tablist')).not.toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('하드 새로고침(새 QueryClient, 캐시 없음)에서는 유효 세션이 정상 복원되어 보호 라우트가 튕기지 않는다(회귀)', async () => {
    server.use(
      http.get('/api/users/me', () => {
        const success: ApiResponse<User> = { success: true, data: mockUser };
        return HttpResponse.json(success);
      }),
    );

    renderApp();

    await waitFor(() => {
      expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    });

    expect(screen.queryByRole('tablist')).toBeNull();
    expect(useAuthStore.getState().user).toEqual(mockUser);
  });
});
