// @vitest-environment jsdom
// #280 P2·P3 후속 하드닝 테스트 — LoginPage 세션체크 refetch 억제 + state.from 오픈 리다이렉트 검증.
import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { AUTH_ME_QUERY_KEY } from '../constants';
import { useAuthStore } from '../store/authStore';
import type { EmailAvailabilityResponse, User } from '../types';
import { LoginPage } from './LoginPage';

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

let getMeCallCount = 0;
let csrfPrimeCallCount = 0;

// useCsrfPrime이 마운트 시 호출하는 GET(더미 이메일 중복확인) — 다른 인증 폼 테스트와 동일 패턴.
const csrfPrimeHandler = http.get('/api/auth/email-availability', () => {
  csrfPrimeCallCount += 1;
  const success: ApiResponse<EmailAvailabilityResponse> = { success: true, data: { available: true } };
  return HttpResponse.json(success);
});

const server = setupServer(csrfPrimeHandler);

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

function mockGetMeSuccess(user: User = mockUser) {
  server.use(
    http.get('/api/users/me', () => {
      getMeCallCount += 1;
      const success: ApiResponse<User> = { success: true, data: user };
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
    csrfPrimeCallCount = 0;
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

  // #1513 — 기업 대시보드를 쓰지 않는 role이 기존 세션으로 /login에 재방문했을 때, 예전에는
  // PLATFORM_ADMIN 분기가 없어 대시보드로 들어갔다(로그인 훅만 막으면 이쪽으로 뚫린다).
  // 이제는 role 홈이 단일 기준이고, 대시보드에 걸린 allowedRoles와 답이 같아야 루프가 안 난다.
  it('세션이 PLATFORM_ADMIN이면 플랫폼 관리자 콘솔로 이동한다(#1513)', async () => {
    mockGetMeSuccess({ ...mockUser, role: 'PLATFORM_ADMIN', companyId: null });
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
    });
  });

  it('세션이 COUNSELOR면 상담원 대기열로 이동한다', async () => {
    mockGetMeSuccess({ ...mockUser, role: 'COUNSELOR', companyId: null });
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/counsel-console/queue');
    });
  });

  // 차단 role은 state.from(대시보드 하위 경로)이 실려 있어도 role 홈이 이긴다 — 그대로 보내면
  // AppShell의 allowedRoles 가드가 다시 튕겨 리다이렉트가 한 번 더 돈다(#1513).
  it('세션이 차단 role이면 state.from이 있어도 무시하고 role 홈으로 이동한다(#1513)', async () => {
    mockGetMeSuccess({ ...mockUser, role: 'PLATFORM_ADMIN', companyId: null });
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login', state: { from: '/facilities/list' } });

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/platform-admin');
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

  // PR #297 P2 픽스 — LoginHeroPanel(좌측 브랜딩 패널)이 lg 미만에서 hidden 처리되면서,
  // 그 안에만 있던 "기업 통합회원 가입" 진입점이 1024px 미만(태블릿·모바일)에서 완전히
  // 사라지는 회귀가 있었다. 인증 패널 하단에 별도로 렌더되는 모바일 전용 CTA 블록
  // (data-testid="mobile-signup-cta")이 항상 존재하고 정상 동작하는지 고정한다.
  // jsdom은 CSS 미디어쿼리(lg:hidden)를 평가하지 않으므로 실제 반응형 숨김/노출 자체는
  // 검증 대상이 아니다 — "hidden lg:flex" 컨테이너(LoginHeroPanel) 밖에 독립된 CTA 진입점이
  // 구조적으로 존재하고 클릭 시 정상 라우팅되는지를 고정하는 것이 이 테스트의 목적이다.
  it('모바일 전용 회원가입 CTA가 렌더되고 클릭 시 기업 회원가입으로 이동한다(#297 P2)', async () => {
    mockGetMeUnauthorized();
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(getMeCallCount).toBe(1);
    });

    const mobileCta = await screen.findByTestId('mobile-signup-cta');
    const companySignupButton = within(mobileCta).getByRole('button', { name: '기업 통합회원 가입' });

    fireEvent.click(companySignupButton);

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/signup/company');
    });
  });

  // #720 픽스 — LoginHeroPanel(좌측 브랜딩 패널)이 lg 미만에서 hidden 처리되면서, 그 안에만
  // 있던 브랜드 로고(홈 진입점)가 1024px 미만(태블릿·모바일)에서 완전히 사라지는 회귀가 있었다.
  // mobile-signup-cta(#297 P2)와 동일 패턴으로, 인증 패널 상단에 별도로 렌더되는 모바일 전용
  // 로고 진입점(data-testid="mobile-brand-logo")이 항상 존재하고 클릭 시 랜딩(홈)으로 이동하는지
  // 고정한다. jsdom은 CSS 미디어쿼리(lg:hidden)를 평가하지 않으므로 실제 반응형 숨김/노출
  // 자체는 검증 대상이 아니다 — "hidden lg:flex" 컨테이너(LoginHeroPanel) 밖에 독립된 로고
  // 진입점이 구조적으로 존재하고 클릭 시 정상 라우팅되는지를 고정하는 것이 이 테스트의 목적이다.
  it('모바일 전용 브랜드 로고가 렌더되고 클릭 시 랜딩(홈)으로 이동한다(#720)', async () => {
    mockGetMeUnauthorized();
    const queryClient = new QueryClient();
    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(getMeCallCount).toBe(1);
    });

    const mobileBrandLogo = await screen.findByTestId('mobile-brand-logo');
    const homeLink = within(mobileBrandLogo).getByRole('link', { name: 'HajaCheck 홈으로' });

    fireEvent.click(homeLink);

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/');
    });
  });

  // #1200 회귀 — 로그아웃(useLogout)은 ['auth','me']를 settled-null로 고정하므로, /login으로
  // 전환돼 LoginPage가 마운트돼도 staleTime(5s) 내에는 getMe가 나가지 않는다. 예전에는 CSRF
  // 프라이밍이 그 getMe 쿼리에 얹혀 있어서 프라이밍이 통째로 소실됐고, XSRF-TOKEN 쿠키 없이
  // 보낸 재로그인 첫 POST가 403으로 실패했다(두 번째 시도에 성공). 프라이밍은 세션 조회 캐시와
  // 무관한 전용 GET(useCsrfPrime)이어야 한다.
  it('로그아웃으로 세션조회 캐시가 고정돼 getMe가 안 나가도 CSRF 프라이밍 GET은 발생한다(#1200)', async () => {
    mockGetMeUnauthorized();
    const queryClient = new QueryClient();
    // useLogout이 로그아웃 직후 하는 것과 동일한 캐시 고정
    queryClient.setQueryData(AUTH_ME_QUERY_KEY, null);

    renderLoginPage(queryClient, { pathname: '/login' });

    await waitFor(() => {
      expect(csrfPrimeCallCount).toBe(1);
    });
    // 로그인 폼은 그대로 노출되고(캐시가 null이라 리다이렉트 없음),
    expect(screen.getByRole('tablist')).not.toBeNull();
    // getMe는 캐시가 fresh라 나가지 않는다 — #280 P3 / PR #232 P2-D 방어장치가 그대로 유지되는지 고정.
    expect(getMeCallCount).toBe(0);
  });
});
