// @vitest-environment jsdom
// 플랫폼 관리자 로그인(#535) — Figma node 973-2520. 개인/기업 탭 없는 단일 아이디/비밀번호 폼이며,
// 전용 엔드포인트(POST /api/auth/platform-admin/login)로만 로그인한다(#1513). role 강제는 서버가
// 하므로 PLATFORM_ADMIN이 아니면 세션 자체가 발급되지 않고 403 AUTH_ROLE_NOT_ALLOWED가 온다 —
// 예전의 "로그인 성공 후 logout()으로 세션 되돌리기"는 되돌릴 세션이 없어져 삭제됐고, 이 테스트는
// 그 롤백이 되살아나지 않는지(logout 미호출)까지 함께 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../../auth/store/authStore';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import {
  loadInspectionCreateDraft,
  saveInspectionCreateDraft,
} from '../../inspection/utils/inspectionCreateDraft';
import { clearDraftMediaFiles } from '../../inspection/utils/inspectionCreateDraftFiles';
import { getRagSessionId, setRagSessionId } from '../../support/utils/ragSessionId';
import type { EmailAvailabilityResponse, User } from '../../auth/types';
import { PlatformAdminLoginPage } from './PlatformAdminLoginPage';

// jsdom엔 기본적으로 indexedDB가 없어(fake-indexeddb 전역 폴리필 미설정) 실제 clearDraftMediaFiles
// 구현을 그대로 쓰면 openDb()가 조용히 실패해(자체 try/catch로 삼킴) 호출 여부를 관찰할 수 없다 —
// InspectionCreatePage.test.tsx와 동일한 이유로 이 모듈만 스파이 가능한 목으로 교체한다.
vi.mock('../../inspection/utils/inspectionCreateDraftFiles', () => ({
  saveDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
  loadDraftMediaFiles: vi.fn().mockResolvedValue([]),
  clearDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
}));

const platformAdminUser: User = {
  id: 1,
  email: 'platform-admin@example.com',
  name: '플랫폼 운영진',
  role: 'PLATFORM_ADMIN',
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
// 서버가 다른 허용 role을 적용해 플랫폼 관리자가 로그인할 수 없게 된다(dev가 403으로 막히던 그 상태).
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
  localStorage.clear();
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
    http.post('/api/auth/platform-admin/login', () => {
      const success: ApiResponse<User> = { success: true, data: user };
      return HttpResponse.json(success);
    }),
  );
}

/** 서버 role 게이트 거절(#1513) — 인증은 통과했지만 허용 role이 아니라 세션 미발급 + 403. */
function mockRoleNotAllowed() {
  server.use(
    http.post('/api/auth/platform-admin/login', () => {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_ROLE_NOT_ALLOWED', message: '이 화면으로는 로그인할 수 없는 계정입니다.' },
      };
      return HttpResponse.json(failure, { status: 403 });
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

  // #1194 — localStorage 영속 스토어라 공용 PC에서 계정이 바뀌면 이전 사용자의 회차 id가 남는다.
  // 로그인 진입점 3곳(useLogin·usePlatformAdminLogin·useCounselorLogin)이 같은 계약을 지켜야 하는데
  // 이 훅에만 없었다.
  it('로그인 성공 시 이전 세션의 activeInspectionId/activeReportId를 지운다(#1194)', async () => {
    useInspectionStore.getState().setActiveInspectionId(42);
    useInspectionStore.getState().setActiveReportId(7);
    mockLoginSuccess(platformAdminUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
    expect(useInspectionStore.getState().activeInspectionId).toBeNull();
    expect(useInspectionStore.getState().activeReportId).toBeNull();
  });

  // #1590 — 같은 이유(공용 PC 계정 전환)로 RAG 챗봇 session_id(localStorage 영속)도 함께 지운다.
  it('로그인 성공 시 이전 사용자의 RAG session_id를 지운다(#1590)', async () => {
    setRagSessionId(77);
    mockLoginSuccess(platformAdminUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
    expect(getRagSessionId()).toBeNull();
  });

  // #1703 / PR #1708 2차 P1 — clearPreviousUserLocalState 도입 전에는 usePlatformAdminLogin이
  // 점검 생성 폼 임시저장을 지우지 않아, 공용 PC에서 플랫폼 관리자 로그인 전 사용자의 시설물·
  // 메모가 그대로 남았다. 최소한 이 훅이 clearPreviousUserLocalState를 호출한다는 사실을 고정한다.
  it('로그인 성공 시 이전 사용자의 점검 생성 폼 임시저장(localStorage 텍스트+IndexedDB 사진)도 지운다', async () => {
    vi.mocked(clearDraftMediaFiles).mockClear();
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'DETAILED',
      memo: '이전 사용자가 입력한 메모',
    });
    expect(loadInspectionCreateDraft()).not.toBeNull();

    mockLoginSuccess(platformAdminUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
    expect(loadInspectionCreateDraft()).toBeNull();
    expect(clearDraftMediaFiles).toHaveBeenCalled();
  });

  // #1513 — 화면↔엔드포인트 대응이 이 PR의 핵심 계약이다. 기업 포털(/api/auth/login)로 새면
  // 서버가 ADMIN/INSPECTOR/USER 기준으로 판정해 플랫폼 관리자 로그인이 통째로 403이 된다.
  it('플랫폼 관리자 전용 엔드포인트(POST /api/auth/platform-admin/login)로 요청한다(#1513)', async () => {
    mockLoginSuccess(platformAdminUser);
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
    expect(requestedPaths).toContain('/api/auth/platform-admin/login');
    expect(requestedPaths).not.toContain('/api/auth/login');
  });

  // #1513 — role 판정은 서버가 한다. 허용 role이 아니면 세션이 발급되지 않은 채 403이 오므로,
  // 프론트는 안내만 하고 setUser/이동을 하지 않는다. 되돌릴 세션이 없으니 logout도 호출하지 않는다
  // (롤백 로직 부활 방지 — logoutCallCount 0 단언이 그 회귀를 잡는다).
  it('403 AUTH_ROLE_NOT_ALLOWED면 안내만 하고 setUser·logout 없이 로그인 화면에 머문다', async () => {
    mockRoleNotAllowed();
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toBe('플랫폼 관리자 계정이 아닙니다.');
    });
    expect(useAuthStore.getState().user).toBeNull();
    expect(logoutCallCount).toBe(0);
    expect(screen.getByTestId('location').textContent).toBe('/platform-admin/login');
  });

  it('잘못된 자격증명(401)이면 안내 메시지를 표시한다', async () => {
    server.use(
      http.post('/api/auth/platform-admin/login', () => {
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

  // #1200 — CSRF 토큰 누락·만료로 로그인 POST가 403 FORBIDDEN으로 거부될 때, 매핑이 없어
  // 기본 문구("로그인에 실패했습니다")가 떠서 자격 증명 오류로 오인됐다(CompanyLoginTab과 동일 경로).
  it('403 FORBIDDEN(CSRF)이면 재시도 안내 문구를 표시한다(#1200)', async () => {
    server.use(
      http.post('/api/auth/platform-admin/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FORBIDDEN', message: '접근 권한이 없습니다.' },
        };
        return HttpResponse.json(failure, { status: 403 });
      }),
    );
    renderPage();

    fillAndSubmit();

    await waitFor(() => {
      // role="alert" — 문구뿐 아니라 스크린리더 즉시 노출까지 함께 고정한다(CompanyLoginTab과 동일 규약)
      expect(screen.getByRole('alert').textContent).toBe('요청이 만료되었습니다. 다시 시도해 주세요.');
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
