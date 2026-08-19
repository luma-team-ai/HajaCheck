// @vitest-environment jsdom
// 데모 계정으로 둘러보기(#1627) — POST /api/auth/demo-login 호출·성공 시 setUser+role 홈 이동·
// 이전 세션 잔여 정리(useLogin과 동일 계약)·404/429 판별값을 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import {
  loadInspectionCreateDraft,
  saveInspectionCreateDraft,
} from '../../inspection/utils/inspectionCreateDraft';
import { clearDraftMediaFiles } from '../../inspection/utils/inspectionCreateDraftFiles';
import { getRagSessionId, setRagSessionId } from '../../support/utils/ragSessionId';
import { useAuthStore } from '../store/authStore';
import type { User } from '../types';
import { useDemoLogin } from './useDemoLogin';

// jsdom엔 기본적으로 indexedDB가 없어(fake-indexeddb 전역 폴리필 미설정) 실제 clearDraftMediaFiles
// 구현을 그대로 쓰면 openDb()가 조용히 실패해(자체 try/catch로 삼킴) 호출 여부를 관찰할 수 없다 —
// InspectionCreatePage.test.tsx와 동일한 이유로 이 모듈만 스파이 가능한 목으로 교체한다.
vi.mock('../../inspection/utils/inspectionCreateDraftFiles', () => ({
  saveDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
  loadDraftMediaFiles: vi.fn().mockResolvedValue([]),
  clearDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
}));

const mockDemoUser: User = {
  id: 999,
  email: 'demo@example.com',
  name: '데모 계정',
  role: 'ADMIN',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '데모 회사',
  status: 'ACTIVE',
  isDemo: true,
};

const server = setupServer(
  http.post('/api/auth/demo-login', () => {
    const success: ApiResponse<User> = { success: true, data: mockDemoUser };
    return HttpResponse.json(success);
  }),
);

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
  localStorage.clear();
});
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function DemoLoginButton() {
  const { demoLogin, error, isUnavailable } = useDemoLogin();
  return (
    <>
      <button type="button" onClick={() => demoLogin()}>
        데모 계정으로 둘러보기
      </button>
      {error && <span data-testid="demo-error-status">{error.status}</span>}
      {isUnavailable && <span data-testid="demo-unavailable">true</span>}
    </>
  );
}

function renderWithProviders() {
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <DemoLoginButton />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('useDemoLogin', () => {
  beforeEach(() => {
    useInspectionStore.getState().setActiveInspectionId(42);
    useInspectionStore.getState().setActiveReportId(7);
    setRagSessionId(77);
  });

  it('POST /api/auth/demo-login으로 요청하고 성공 시 role 홈으로 이동한다', async () => {
    renderWithProviders();

    fireEvent.click(screen.getByText('데모 계정으로 둘러보기'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));
    expect(requestedPaths).toEqual(['/api/auth/demo-login']);
    expect(useAuthStore.getState().user).toEqual(mockDemoUser);
  });

  // useLogin과 동일 계약(#1194, #1590) — 공용 PC에서 데모 로그인 전 사용자의 잔여 상태가 새 세션에
  // 노출되면 안 된다.
  it('성공 시 이전 세션의 activeInspectionId/activeReportId/RAG session_id를 지운다', async () => {
    renderWithProviders();

    fireEvent.click(screen.getByText('데모 계정으로 둘러보기'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));
    expect(useInspectionStore.getState().activeInspectionId).toBeNull();
    expect(useInspectionStore.getState().activeReportId).toBeNull();
    expect(getRagSessionId()).toBeNull();
  });

  // #1703 / PR #1708 2차 P1 — clearPreviousUserLocalState 도입 전에는 useDemoLogin이 점검 생성
  // 폼 임시저장을 지우지 않아, 공용 PC에서 데모 로그인 전 사용자의 시설물·메모가 그대로 남았다.
  // 최소한 이 훅이 clearPreviousUserLocalState를 호출한다는 사실을(localStorage 텍스트 초안이
  // 지워지고 IndexedDB 정리도 시도되는지로) 고정한다.
  it('성공 시 이전 사용자의 점검 생성 폼 임시저장(localStorage 텍스트+IndexedDB 사진)도 지운다', async () => {
    vi.mocked(clearDraftMediaFiles).mockClear();
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: '2026-08-01',
      inspectionType: 'DETAILED',
      memo: '이전 사용자가 입력한 메모',
    });
    expect(loadInspectionCreateDraft()).not.toBeNull();

    renderWithProviders();

    fireEvent.click(screen.getByText('데모 계정으로 둘러보기'));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/dashboard'));
    expect(loadInspectionCreateDraft()).toBeNull();
    expect(clearDraftMediaFiles).toHaveBeenCalled();
  });

  // 계약(handoff) — 비활성 시 404 계열. isUnavailable로 화면이 버튼을 숨길 수 있게 판별값을 낸다.
  it('404 응답이면 setUser·이동 없이 isUnavailable=true를 낸다', async () => {
    server.use(
      http.post('/api/auth/demo-login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'DEMO_LOGIN_DISABLED', message: '데모 로그인이 비활성화되어 있습니다.' },
        };
        return HttpResponse.json(failure, { status: 404 });
      }),
    );
    renderWithProviders();

    fireEvent.click(screen.getByText('데모 계정으로 둘러보기'));

    await waitFor(() => expect(screen.getByTestId('demo-unavailable').textContent).toBe('true'));
    expect(useAuthStore.getState().user).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/login');
  });

  // rate limit(429)은 비활성과 달리 재시도 여지가 있으므로 isUnavailable=false를 유지해야 한다
  // (화면이 버튼을 계속 노출).
  it('429 응답이면 isUnavailable=false를 유지하고 error.status=429를 낸다', async () => {
    server.use(
      http.post('/api/auth/demo-login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'RATE_LIMITED', message: '요청이 너무 많습니다.' },
        };
        return HttpResponse.json(failure, { status: 429 });
      }),
    );
    renderWithProviders();

    fireEvent.click(screen.getByText('데모 계정으로 둘러보기'));

    await waitFor(() => expect(screen.getByTestId('demo-error-status').textContent).toBe('429'));
    expect(screen.queryByTestId('demo-unavailable')).toBeNull();
  });
});
