// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
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
});
