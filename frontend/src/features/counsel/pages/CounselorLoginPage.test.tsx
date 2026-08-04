// @vitest-environment jsdom
// 상담원 전용 로그인 — PlatformAdminLoginPage.test.tsx와 동일 커버리지(#535 패턴). 개인/기업 탭
// 없는 단일 아이디/비밀번호 폼이며, 전용 엔드포인트(POST /api/auth/counselor/login)로만
// 로그인한다(#1513). role 강제는 서버가 하므로 COUNSELOR가 아니면 세션 자체가 발급되지 않고
// 403 AUTH_ROLE_NOT_ALLOWED가 온다 — 롤백용 logout()은 되돌릴 세션이 없어져 삭제됐고, 이 테스트는
// 그 롤백이 되살아나지 않는지(logout 미호출)까지 함께 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../../auth/store/authStore';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
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

// role 불일치 계정으로 "로그인에 성공하는" 케이스는 더 이상 존재하지 않는다(#1513) — 서버가
// 403으로 끊고 세션을 주지 않으므로, 거절 경로는 mockRoleNotAllowed()로 표현한다.

// 롤백용 logout이 되살아나지 않는지 감시한다(#1513으로 삭제된 로직).
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

// 이 화면이 실제로 어느 경로로 POST 하는지 기록한다(#1513) — 기업 포털(/api/auth/login)로 새면
// 서버가 다른 허용 role을 적용해 상담원 로그인이 통째로 403이 된다.
const requestedPaths: string[] = [];
server.events.on('request:start', ({ request }) => {
  requestedPaths.push(new URL(request.url).pathname);
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
  logoutCallCount = 0;
  requestedPaths.length = 0;
  useInspectionStore.getState().clearActiveInspectionId();
  useInspectionStore.getState().clearActiveReportId();
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
    http.post('/api/auth/counselor/login', () => {
      const success: ApiResponse<User> = { success: true, data: user };
      return HttpResponse.json(success);
    }),
  );
}

/** 서버 role 게이트 거절(#1513) — 인증은 통과했지만 허용 role이 아니라 세션 미발급 + 403. */
function mockRoleNotAllowed() {
  server.use(
    http.post('/api/auth/counselor/login', () => {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_ROLE_NOT_ALLOWED', message: '이 화면으로는 로그인할 수 없는 계정입니다.' },
      };
      return HttpResponse.json(failure, { status: 403 });
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

  // #1194 — 로그인 진입점 3곳이 같은 계약을 지키는지 고정한다(이 훅에만 없었다).
  it('로그인 성공 시 이전 세션의 activeInspectionId/activeReportId를 지운다(#1194)', async () => {
    useInspectionStore.getState().setActiveInspectionId(42);
    useInspectionStore.getState().setActiveReportId(7);
    mockLoginSuccess(counselorUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/counsel-console/queue');
    });
    expect(useInspectionStore.getState().activeInspectionId).toBeNull();
    expect(useInspectionStore.getState().activeReportId).toBeNull();
  });

  // #1513 — 화면↔엔드포인트 대응이 이 PR의 핵심 계약이다. 기업 포털(/api/auth/login)로 새면
  // 서버가 ADMIN/INSPECTOR/USER 기준으로 판정해 상담원 로그인이 통째로 403이 된다.
  it('상담원 전용 엔드포인트(POST /api/auth/counselor/login)로 요청한다(#1513)', async () => {
    mockLoginSuccess(counselorUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/counsel-console/queue');
    });
    expect(requestedPaths).toContain('/api/auth/counselor/login');
    expect(requestedPaths).not.toContain('/api/auth/login');
  });

  // #1513 — role 판정은 서버가 한다. 허용 role이 아니면 세션이 발급되지 않은 채 403이 오므로,
  // 프론트는 안내만 하고 setUser/이동을 하지 않는다. 되돌릴 세션이 없으니 logout도 호출하지 않는다.
  it('403 AUTH_ROLE_NOT_ALLOWED면 안내만 하고 setUser·logout 없이 로그인 화면에 머문다', async () => {
    mockRoleNotAllowed();
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toBe('상담원 계정이 아닙니다.');
    });
    expect(useAuthStore.getState().user).toBeNull();
    expect(logoutCallCount).toBe(0);
    expect(screen.getByTestId('location').textContent).toBe('/counsel-console/login');
  });

  it('잘못된 자격증명(401)이면 안내 메시지를 표시한다', async () => {
    server.use(
      http.post('/api/auth/counselor/login', () => {
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
