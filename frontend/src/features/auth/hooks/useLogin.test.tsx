// @vitest-environment jsdom
// PR머신 리뷰 P2(#1194) — localStorage 영속화된 inspectionStore가 로그인 성공 시점에 비워지는지
// 고정한다. 공용 PC에서 이전 세션의 activeInspectionId/activeReportId가 다음 로그인 사용자에게
// 노출되던 것을 막는 유일한 방어선(useLogin.onSuccess)이라, 이 테스트가 없으면 향후 리팩터로
// clear 호출이 조용히 사라져도 아무것도 깨지지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { getRagSessionId, setRagSessionId } from '../../support/utils/ragSessionId';
import { useAuthStore } from '../store/authStore';
import type { User } from '../types';
import { useLogin } from './useLogin';

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

// 기업회원 탭은 기업 포털 엔드포인트만 쓴다(#1513) — onUnhandledRequest:'error'라, 훅이 다른
// 경로(예: 플랫폼 관리자용)로 POST 하면 이 핸들러에 걸리지 않고 테스트가 즉시 실패한다.
const server = setupServer(
  http.post('/api/auth/login', () => {
    const success: ApiResponse<User> = { success: true, data: mockUser };
    return HttpResponse.json(success);
  }),
);

// 실제 요청 경로 기록 — "이 훅이 /api/auth/login으로 POST 한다"를 명시적으로 고정한다.
const requestedPaths: string[] = [];
server.events.on('request:start', ({ request }) => {
  requestedPaths.push(new URL(request.url).pathname);
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
  useInspectionStore.getState().clearActiveInspectionId();
  useInspectionStore.getState().clearActiveReportId();
  requestedPaths.length = 0;
});
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function LoginButton() {
  const { login } = useLogin();
  return (
    <button type="button" onClick={() => login({ loginId: 'hajacheck@example.com', password: 'pw' })}>
      로그인
    </button>
  );
}

function renderWithProviders(initialEntries: Array<string | { pathname: string; state?: unknown }> = ['/login']) {
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <LoginButton />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('useLogin', () => {
  beforeEach(() => {
    useInspectionStore.getState().setActiveInspectionId(42);
    useInspectionStore.getState().setActiveReportId(7);
    setRagSessionId(77);
  });

  // #1590 — RAG 챗봇 session_id도 localStorage 영속이라 계정이 바뀌면 지워야 한다. 남으면 새
  // 사용자의 첫 질의가 이전 사용자의 세션으로 나가 403으로 실패한다(#1194와 같은 계약).
  it('로그인 성공 시 이전 사용자의 RAG session_id를 지운다', async () => {
    renderWithProviders();

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));

    expect(getRagSessionId()).toBeNull();
  });

  it('로그인 성공 시 이전 세션의 activeInspectionId/activeReportId를 지운다(공용 PC 크로스유저 노출 방지)', async () => {
    renderWithProviders();

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));

    expect(useInspectionStore.getState().activeInspectionId).toBeNull();
    expect(useInspectionStore.getState().activeReportId).toBeNull();
    expect(useAuthStore.getState().user).toEqual(mockUser);
  });

  // #1442 — ProtectedRoute가 비로그인 접근 시 state.from으로 보존한 원래 목적지로 로그인 후
  // 복귀해야 한다(지금까지는 '/dashboard'로 항상 고정 이동해 복귀 의도가 무시됐다).
  it('state.from이 안전한 내부 경로면 로그인 후 그 경로로 이동한다', async () => {
    renderWithProviders([{ pathname: '/login', state: { from: '/facilities/list' } }]);

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/facilities/list'));
  });

  // state.from은 라우터 state에 실린 값이라 외부에서 임의로 주입 가능 — 오픈 리다이렉트 시도값은
  // isSafeInternalPath 검증에 걸려 fallback으로 폴백해야 한다(#280 P3와 동일한 위험 방어).
  it('state.from이 오픈 리다이렉트 시도(//evil.com)면 fallback(/dashboard)으로 폴백한다', async () => {
    renderWithProviders([{ pathname: '/login', state: { from: '//evil.com' } }]);

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));
  });

  it('state.from이 없으면 기본 목적지(/dashboard)로 이동한다', async () => {
    renderWithProviders(['/login']);

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));
  });

  // #1513 — 기업 탭은 기업 포털 엔드포인트로만 로그인한다. 경로가 바뀌면 서버 role 게이트가
  // 통째로 다른 포털 것으로 적용되므로(허용 role이 달라진다), 경로 자체를 계약으로 고정한다.
  it('기업 포털 엔드포인트(POST /api/auth/login)로 요청한다', async () => {
    renderWithProviders(['/login']);

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));
    expect(requestedPaths).toEqual(['/api/auth/login']);
  });

  // #1513 — 허용 role이 아니면 서버가 세션을 발급하지 않고 403 AUTH_ROLE_NOT_ALLOWED를 준다.
  // 훅은 성공 처리(setUser·navigate)를 하지 않고 error로만 노출해야 한다(문구는 화면 책임).
  it('403 AUTH_ROLE_NOT_ALLOWED면 setUser·이동 없이 로그인 화면에 머문다', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'AUTH_ROLE_NOT_ALLOWED', message: '이 화면으로는 로그인할 수 없는 계정입니다.' },
        };
        return HttpResponse.json(failure, { status: 403 });
      }),
    );
    renderWithProviders(['/login']);

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() => expect(requestedPaths).toEqual(['/api/auth/login']));
    // 되돌릴 세션이 없으므로 롤백용 logout 호출도 없어야 한다(핸들러 미등록 + onUnhandledRequest:'error').
    expect(useAuthStore.getState().user).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/login');
  });
});
