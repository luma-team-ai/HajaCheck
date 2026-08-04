// @vitest-environment jsdom
// 로그인 진입점 3곳(useLogin·usePlatformAdminLogin·useCounselorLogin)이 지켜야 하는 "이전 사용자
// 잔여 상태 정리" 계약을 이 훅에서도 고정한다 — inspectionStore(#1194)와 RAG session_id(#1590)는
// 둘 다 localStorage 영속이라, 공용 PC에서 계정이 바뀌면 반드시 지워져야 한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { COUNSELOR_QUEUE_ROUTE } from '../../../shared/constants/routes';
import { useAuthStore } from '../../auth/store/authStore';
import type { User } from '../../auth/types';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { getRagSessionId, setRagSessionId } from '../../support/utils/ragSessionId';
import { useCounselorLogin } from './useCounselorLogin';

const counselorUser: User = {
  id: 9,
  email: 'counselor@example.com',
  name: '상담원',
  role: 'COUNSELOR',
  companyId: null,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: null,
  status: 'ACTIVE',
};

const server = setupServer(
  http.post('/api/auth/counselor/login', () => {
    const success: ApiResponse<User> = { success: true, data: counselorUser };
    return HttpResponse.json(success);
  }),
);

// 상담원 탭은 상담원 전용 엔드포인트만 쓴다(#1513) — 경로가 새면 서버 role 게이트가 달라진다.
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
  const { login } = useCounselorLogin();
  return (
    <button type="button" onClick={() => login({ loginId: 'counselor@example.com', password: 'pw' })}>
      로그인
    </button>
  );
}

function renderWithProviders() {
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/counsel-console/login']}>
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

describe('useCounselorLogin', () => {
  beforeEach(() => {
    useInspectionStore.getState().setActiveInspectionId(42);
    useInspectionStore.getState().setActiveReportId(7);
    setRagSessionId(77);
  });

  it('로그인 성공 시 상담원 대기열로 이동하고 이전 세션의 회차 id를 지운다(#1194)', async () => {
    renderWithProviders();

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(COUNSELOR_QUEUE_ROUTE),
    );

    expect(requestedPaths).toEqual(['/api/auth/counselor/login']);
    expect(useInspectionStore.getState().activeInspectionId).toBeNull();
    expect(useInspectionStore.getState().activeReportId).toBeNull();
    expect(useAuthStore.getState().user).toEqual(counselorUser);
  });

  it('로그인 성공 시 이전 사용자의 RAG session_id를 지운다(#1590)', async () => {
    renderWithProviders();

    fireEvent.click(screen.getByText('로그인'));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(COUNSELOR_QUEUE_ROUTE),
    );

    expect(getRagSessionId()).toBeNull();
  });
});
