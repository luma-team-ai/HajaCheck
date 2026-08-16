// @vitest-environment jsdom
// #846 — 로그인 실패 안내가 일반 <p>로 렌더돼 스크린리더가 실패 사유를 읽어주지 않던 문제.
// role="alert" 부여 후 getByRole('alert')로 실패 문구를 찾을 수 있는지 고정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useAuthStore } from '../store/authStore';
import { CompanyLoginTab } from './CompanyLoginTab';

const server = setupServer();

// 기업 탭이 실제로 어느 경로로 POST 하는지 기록한다(#1513) — 화면↔엔드포인트 대응이 이 PR의
// 핵심 계약이라, 경로가 바뀌면 서버 role 게이트가 다른 포털 기준으로 적용된다.
const requestedPaths: string[] = [];
server.events.on('request:start', ({ request }) => {
  requestedPaths.push(new URL(request.url).pathname);
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
  requestedPaths.length = 0;
});
afterAll(() => server.close());

function renderTab() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <CompanyLoginTab />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('CompanyLoginTab', () => {
  it('로그인 실패 시 role="alert"로 실패 문구를 찾을 수 있다', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'AUTH_INVALID_CREDENTIALS', message: '아이디 또는 비밀번호가 올바르지 않습니다.' },
        };
        return HttpResponse.json(failure, { status: 401 });
      }),
    );
    renderTab();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'hajacheck' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('아이디 또는 비밀번호가 올바르지 않습니다.'),
    );
  });

  // #1200 — CSRF 토큰 누락·만료로 로그인 POST가 403 FORBIDDEN으로 거부될 때, 매핑이 없어
  // 기본 문구("로그인에 실패했습니다")가 떠서 자격 증명 오류로 오인됐다. 재시도 유도 문구로 고정.
  it('403 FORBIDDEN(CSRF)은 재시도 안내 문구로 표시한다(#1200)', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FORBIDDEN', message: '접근 권한이 없습니다.' },
        };
        return HttpResponse.json(failure, { status: 403 });
      }),
    );
    renderTab();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'hajacheck' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'pw12345678' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('요청이 만료되었습니다. 다시 시도해 주세요.'),
    );
  });

  // #1513 — 서버가 기업 포털 허용 role(ADMIN/INSPECTOR/USER)이 아닌 계정을 403
  // AUTH_ROLE_NOT_ALLOWED로 거절한다. 매핑이 없으면 기본 문구("로그인에 실패했습니다")로
  // 오안내되고, 사용자는 비밀번호가 틀린 줄 안다.
  it('403 AUTH_ROLE_NOT_ALLOWED면 전용 안내 문구를 표시하고 role은 노출하지 않는다(#1513)', async () => {
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
    renderTab();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'platform-admin' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'pw12345678' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('이 계정으로는 기업회원 로그인을 사용할 수 없습니다.');
    // 계정 오라클 방지 — 어떤 role인지, 어느 콘솔 계정인지 문구로 알려주지 않는다.
    expect(alert.textContent).not.toMatch(/관리자|상담원|PLATFORM_ADMIN|COUNSELOR/);
    // 세션이 발급되지 않았으므로 authStore에도 커밋되지 않는다.
    expect(useAuthStore.getState().user).toBeNull();
  });

  // 기업 탭은 기업 포털 엔드포인트로만 로그인한다 — 경로가 바뀌면 적용되는 허용 role이 통째로 달라진다.
  it('기업 포털 엔드포인트(POST /api/auth/login)로 요청한다(#1513)', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'AUTH_INVALID_CREDENTIALS', message: '아이디 또는 비밀번호가 올바르지 않습니다.' },
        };
        return HttpResponse.json(failure, { status: 401 });
      }),
    );
    renderTab();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'hajacheck' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'pw12345678' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    await screen.findByRole('alert');
    expect(requestedPaths).toEqual(['/api/auth/login']);
  });

  // 데모 계정으로 둘러보기(#1627) — 크레덴셜 없이 원클릭 진입, POST /api/auth/demo-login으로 요청한다.
  it('데모 계정으로 둘러보기 버튼 클릭 시 POST /api/auth/demo-login으로 요청한다', async () => {
    server.use(
      http.post('/api/auth/demo-login', () => {
        const success: ApiResponse<{
          id: number;
          email: string;
          name: string;
          role: string;
          companyId: number;
          profileImageUrl: null;
          createdAt: string;
          companyName: string;
          status: string;
          isDemo: boolean;
        }> = {
          success: true,
          data: {
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
          },
        };
        return HttpResponse.json(success);
      }),
    );
    renderTab();

    fireEvent.click(screen.getByRole('button', { name: '데모 계정으로 둘러보기' }));

    await waitFor(() => expect(requestedPaths).toEqual(['/api/auth/demo-login']));
    expect(useAuthStore.getState().user?.isDemo).toBe(true);
  });

  // 계약(handoff) — 비활성 시 404 계열. 재시도해도 계속 실패하므로 버튼을 숨기고 안내로 대체한다.
  it('데모 로그인이 404(비활성)면 버튼을 숨기고 안내 문구를 표시한다', async () => {
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
    renderTab();

    fireEvent.click(screen.getByRole('button', { name: '데모 계정으로 둘러보기' }));

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe('지금은 데모 계정 체험을 이용할 수 없습니다.'),
    );
    expect(screen.queryByRole('button', { name: '데모 계정으로 둘러보기' })).toBeNull();
  });

  // 429는 재시도 여지가 있으므로 버튼을 계속 노출하고 재시도 안내 문구만 보여준다.
  it('데모 로그인이 429(rate limit)면 버튼은 유지하고 재시도 안내 문구를 표시한다', async () => {
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
    renderTab();

    fireEvent.click(screen.getByRole('button', { name: '데모 계정으로 둘러보기' }));

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        '데모 계정 이용이 많아 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.',
      ),
    );
    expect(screen.getByRole('button', { name: '데모 계정으로 둘러보기' })).not.toBeNull();
  });
});
