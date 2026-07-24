// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../features/auth/store/authStore';
import type { User } from '../features/auth/types';
import { ProtectedRoute } from '../shared/components/ProtectedRoute';
import type { ApiResponse } from '../shared/api/types';
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

function renderWithGate(children: ReactNode) {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthGate>{children}</AuthGate>
    </QueryClientProvider>,
  );
}

describe('AuthGate', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
  });

  it('getMe가 pending인 동안 스플래시를 렌더하고, settle 후 children을 렌더한다', async () => {
    server.use(
      http.get('/api/users/me', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        const success: ApiResponse<User> = { success: true, data: mockUser };
        return HttpResponse.json(success);
      }),
    );

    renderWithGate(<div>라우터 렌더됨</div>);

    expect(screen.getByRole('status')).not.toBeNull();
    expect(screen.queryByText('라우터 렌더됨')).toBeNull();

    await waitFor(() => {
      expect(screen.getByText('라우터 렌더됨')).not.toBeNull();
    });

    expect(screen.queryByRole('status')).toBeNull();
    expect(useAuthStore.getState().user).toEqual(mockUser);
  });

  it('getMe 401(미로그인)이어도 settle 후 children을 렌더한다(user=null 유지)', async () => {
    server.use(
      http.get('/api/users/me', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' },
        };
        return HttpResponse.json(failure, { status: 401 });
      }),
    );

    renderWithGate(<div>라우터 렌더됨</div>);

    await waitFor(() => {
      expect(screen.getByText('라우터 렌더됨')).not.toBeNull();
    });

    expect(useAuthStore.getState().user).toBeNull();
  });

  it('인증된 세션(getMe 200)에서는 새로고침 시 보호 라우트가 /login으로 튕기지 않는다', async () => {
    server.use(
      http.get('/api/users/me', () => {
        const success: ApiResponse<User> = { success: true, data: mockUser };
        return HttpResponse.json(success);
      }),
    );

    renderWithGate(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>대시보드 콘텐츠</div>
              </ProtectedRoute>
            }
          />
          <Route path="/login" element={<div>로그인 페이지</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('대시보드 콘텐츠')).not.toBeNull();
    });

    expect(screen.queryByText('로그인 페이지')).toBeNull();
  });

  it('getMe 200 + 빈 응답(data=null)이면 스플래시에서 빠져나와 /login으로 리다이렉트된다(P2-B, 데드락 방지)', async () => {
    server.use(
      http.get('/api/users/me', () => {
        const empty: ApiResponse<null> = { success: true, data: null };
        return HttpResponse.json(empty);
      }),
    );

    renderWithGate(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>대시보드 콘텐츠</div>
              </ProtectedRoute>
            }
          />
          <Route path="/login" element={<div>로그인 페이지</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('로그인 페이지')).not.toBeNull();
    });

    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText('대시보드 콘텐츠')).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('getMe 500이면 스플래시 대신 에러+재시도 UI를 보여주고, 재시도 클릭 시 refetch가 재요청한다(P2-A)', async () => {
    let callCount = 0;
    server.use(
      http.get('/api/users/me', () => {
        callCount += 1;
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다.' },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    renderWithGate(<div>라우터 렌더됨</div>);

    await waitFor(() => {
      expect(screen.getByRole('alert')).not.toBeNull();
    });

    expect(screen.queryByText('라우터 렌더됨')).toBeNull();
    expect(
      screen.getByText('로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'),
    ).not.toBeNull();
    expect(callCount).toBe(1);

    fireEvent.click(screen.getByText('다시 시도'));

    await waitFor(() => {
      expect(callCount).toBe(2);
    });
  });
});
