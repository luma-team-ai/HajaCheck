// @vitest-environment jsdom
// PasswordChangeSection(#1316, HAJA-602) 단위 테스트 — 폼 검증(비활성 조건), 성공/세션만료 시
// /login 이동(로그아웃 네트워크 지연에도 안내 문구가 유지되는지 포함, P2-1 회귀 방지), 401
// 화이트리스트 분기(SESSION_EXPIRED만 로그아웃, 그 외는 전부 현재 비밀번호 오류, P2-2)·400·429·
// 미분류(500) 에러 분기, 에러 경로에서의 mutation 캐시 정리(보안 리뷰 P3-2)를 검증한다.
// 백엔드(#1315)는 병렬 구현 중이라 msw 핸들러(mypageApi.handlers.ts)로 계약(handoff)을 그대로
// 흉내낸다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../../auth/store/authStore';
import { MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER, mypageHandlers } from '../api/mypageApi.handlers';
import { PasswordChangeSection, REDIRECT_DELAY_MS } from './PasswordChangeSection';

let logoutCallCount = 0;

const server = setupServer(
  ...mypageHandlers,
  http.post('/api/auth/logout', () => {
    logoutCallCount += 1;
    const body: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(body);
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
  logoutCallCount = 0;
});
afterAll(() => server.close());

// useLogout.test.tsx와 동일한 폴링 방식 waitFor — 실 타이머(REDIRECT_DELAY_MS)를 그대로 흘려보낸다.
const waitFor = (predicate: () => boolean, timeout = 5000): Promise<void> => {
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

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function renderSection(): QueryClient {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/mypage/profile']}>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <PasswordChangeSection />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return queryClient;
}

const VALID_NEW_PASSWORD = 'NewPassw0rd!';

function fillForm({
  current = 'CurrentPassw0rd',
  next = VALID_NEW_PASSWORD,
  confirm = VALID_NEW_PASSWORD,
}: { current?: string; next?: string; confirm?: string } = {}) {
  fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: current } });
  fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: next } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: confirm } });
}

function submitButton() {
  return screen.getByRole('button', { name: /비밀번호 변경/ });
}

/** /api/auth/logout 응답을 인위적으로 지연시킨다 — P2-1 회귀 방지 테스트 전용. */
function useDelayedLogoutHandler(delayMs: number) {
  server.use(
    http.post('/api/auth/logout', async () => {
      await delay(delayMs);
      logoutCallCount += 1;
      const body: ApiResponse<null> = { success: true, data: null };
      return HttpResponse.json(body);
    }),
  );
}

describe('PasswordChangeSection', () => {
  it('아무것도 입력하지 않으면 제출 버튼이 비활성화된다', () => {
    renderSection();
    expect(submitButton()).toHaveProperty('disabled', true);
  });

  it('새 비밀번호가 정책(8자 이상 + 영문 + 숫자)을 만족하지 않으면 제출 버튼이 비활성화되고 안내를 보여준다', () => {
    renderSection();
    fillForm({ next: 'short', confirm: 'short' });

    expect(submitButton()).toHaveProperty('disabled', true);
    expect(screen.getByText('8자 이상, 영문+숫자를 포함해 주세요.')).toBeTruthy();
  });

  it('새 비밀번호와 확인이 일치하지 않으면 제출 버튼이 비활성화되고 불일치 안내를 보여준다', () => {
    renderSection();
    fillForm({ confirm: 'Different0rd!' });

    expect(submitButton()).toHaveProperty('disabled', true);
    expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toBeTruthy();
  });

  it('모든 조건을 만족하면 제출 버튼이 활성화된다', () => {
    renderSection();
    fillForm();

    expect(submitButton()).toHaveProperty('disabled', false);
  });

  it('성공 시 안내 문구를 보여주고 잠시 후 클라이언트 세션을 정리해 /login으로 이동한다', async () => {
    const queryClient = renderSection();
    fillForm();

    fireEvent.click(submitButton());

    expect(await screen.findByText('비밀번호가 변경되었습니다. 다시 로그인해 주세요.')).toBeTruthy();

    await waitFor(() => screen.getByTestId('location').textContent === '/login');
    expect(logoutCallCount).toBe(1);
    // 보안 리뷰 P3 — 로그아웃 직전 clearSensitiveState()로 평문 비밀번호(variables)가 담긴
    // MutationCache 엔트리를 정리했는지 확인한다(useLogout의 removeQueries는 query 캐시 전용이라
    // 이 정리가 없으면 gcTime 동안 캐시에 남는다).
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
  });

  // 보안 리뷰 P2-1 회귀 방지 — 리다이렉트 타이머가 clearSensitiveState()(mutation.reset() 포함)를
  // 호출한 뒤에도, logout()의 authApi.logout() 네트워크 왕복이 끝나 navigate('/login')하기 전까지는
  // 완료 안내 문구가 사라지고 빈 폼이 재노출되면 안 된다. /login 도착만 폴링하는 기존 테스트로는 이
  // 중간 깜빡임을 못 잡으므로, 로그아웃 응답을 지연시켜 REDIRECT_DELAY_MS 경과 직후(아직 /login 도착
  // 전) 시점을 명시적으로 검사한다.
  it('로그아웃 네트워크가 지연돼도(성공) /login 이동 전까지 안내 문구가 사라지지 않는다(P2-1 회귀 방지)', async () => {
    const LOGOUT_DELAY_MS = 400;
    useDelayedLogoutHandler(LOGOUT_DELAY_MS);

    renderSection();
    fillForm();
    fireEvent.click(submitButton());

    expect(await screen.findByText('비밀번호가 변경되었습니다. 다시 로그인해 주세요.')).toBeTruthy();

    // REDIRECT_DELAY_MS 경과 직후 — clearSensitiveState()가 이미 실행돼 라이브 mutation 상태는
    // idle로 리셋됐지만, 로그아웃 네트워크가 아직 안 끝나 /login 이동 전이다.
    await sleep(REDIRECT_DELAY_MS + 150);
    expect(screen.getByText('비밀번호가 변경되었습니다. 다시 로그인해 주세요.')).toBeTruthy();
    expect(screen.getByTestId('location').textContent).toBe('/mypage/profile');

    await waitFor(() => screen.getByTestId('location').textContent === '/login');
    expect(logoutCallCount).toBe(1);
  });

  it('401(현재 비밀번호 불일치, AUTH_INVALID_CREDENTIALS)이면 필드 인라인 에러만 보여주고 전역 로그인 리다이렉트로 새지 않는다', async () => {
    const queryClient = renderSection();
    fillForm({ current: MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.wrongCurrentPassword });

    fireEvent.click(submitButton());

    expect(await screen.findByText('현재 비밀번호가 일치하지 않습니다.')).toBeTruthy();
    // 마이페이지 화면에 그대로 머무른다 — axios 인터셉터의 전역 401 하드 리다이렉트(스킵) 검증.
    expect(screen.getByTestId('location').textContent).toBe('/mypage/profile');
    expect(logoutCallCount).toBe(0);
    // 보안 리뷰 P3-2 — 사용자가 화면에 머무는 에러 경로에서도 MutationCache의 평문 variables는
    // 제거되지만, 화면에 보여주는 에러 메시지는 사라지지 않아야 한다(위 findByText로 이미 확인).
    await waitFor(() => queryClient.getMutationCache().getAll().length === 0);
    expect(screen.getByText('현재 비밀번호가 일치하지 않습니다.')).toBeTruthy();
  });

  // 보안 리뷰 P2-2 — 화이트리스트 반전. SESSION_EXPIRED('UNAUTHORIZED') 코드일 때만 세션 만료로
  // 취급해 로그아웃시키고, 그 외 401(코드를 알 수 없는 경우 포함)은 전부 "현재 비밀번호 불일치"로
  // 인라인 처리해야 한다 — "모르는 401은 전부 세션 만료"로 취급하면 가장 흔한 정상 재시도(오타)까지
  // 세션을 파괴한다.
  it('401(세션 만료, code===UNAUTHORIZED)이면 현재 비밀번호 오류로 오인하지 않고 재로그인시킨다', async () => {
    const queryClient = renderSection();
    fillForm({ current: MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.sessionExpired });

    fireEvent.click(submitButton());

    expect(await screen.findByText('세션이 만료되었습니다. 다시 로그인해 주세요.')).toBeTruthy();
    expect(screen.queryByText('현재 비밀번호가 일치하지 않습니다.')).toBeNull();

    await waitFor(() => screen.getByTestId('location').textContent === '/login');
    expect(logoutCallCount).toBe(1);
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
  });

  it('401(코드 미상, UNAUTHORIZED도 AUTH_INVALID_CREDENTIALS도 아님)이면 로그아웃하지 않고 현재 비밀번호 오류로 처리한다(P2-2 화이트리스트 반전)', async () => {
    renderSection();
    fillForm({ current: MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.unknownAuthError });

    fireEvent.click(submitButton());

    expect(await screen.findByText('현재 비밀번호가 일치하지 않습니다.')).toBeTruthy();
    expect(screen.queryByText('세션이 만료되었습니다. 다시 로그인해 주세요.')).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/mypage/profile');
    expect(logoutCallCount).toBe(0);
  });

  it('400(소셜 전용 계정 등 정책 위반, AUTH_PASSWORD_NOT_SET)이면 서버가 내려준 사유를 그대로 보여준다', async () => {
    renderSection();
    fillForm({ current: MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.passwordNotSet });

    fireEvent.click(submitButton());

    expect(
      await screen.findByText('소셜 로그인 전용 계정은 비밀번호를 변경할 수 없습니다.'),
    ).toBeTruthy();
  });

  it('429(요청 과다, AUTH_TOO_MANY_REQUESTS)면 잠시 후 다시 시도하라는 안내를 보여준다', async () => {
    renderSection();
    fillForm({ current: MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.rateLimited });

    fireEvent.click(submitButton());

    expect(await screen.findByText('요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.')).toBeTruthy();
  });

  // 보안 리뷰 P2-2 — 401/400/429 어디에도 안 걸리는 응답(CSRF 403·계정상태 403·500·오프라인 등)이
  // 화면을 완전 무반응으로 만들지 않는지 검증한다(else 폴백).
  it('401/400/429 외 응답(예: 500)이면 무반응 대신 서버 메시지를 else 폴백으로 보여준다', async () => {
    const queryClient = renderSection();
    fillForm({ current: MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.serverError });

    fireEvent.click(submitButton());

    expect(await screen.findByText('일시적인 오류가 발생했습니다.')).toBeTruthy();
    // 보안 리뷰 P3-2 — 미분류 에러 경로에서도 MutationCache의 평문 variables가 정리돼야 한다.
    await waitFor(() => queryClient.getMutationCache().getAll().length === 0);
  });
});
