// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { notificationHandlers } from '../features/notification/api/notificationApi.handlers';
import { MYPAGE_PLAN_ROUTE } from '../features/auth/constants';
import { useAuthStore } from '../features/auth/store/authStore';
import type { User } from '../features/auth/types';
import { AppShellRoute } from './AppShellRoute';

const baseUser: User = {
  id: 1,
  email: 'hajacheck@example.com',
  name: '하자체크 담당자',
  role: 'USER',
  companyId: 1,
  profileImageUrl: null,
};

// 알림 센터(HAJA-38) 연결 후 로그인 사용자 렌더 시 useNotifications가 GET /api/notifications를
// 실제로 호출하므로, 이 파일의 모든 로그인 케이스가 안정적으로 돌게 알림 목 핸들러를 함께 띄운다.
const server = setupServer(...notificationHandlers);
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterAll(() => server.close());

afterEach(() => {
  server.resetHandlers();
  cleanup();
  useAuthStore.setState({ user: null });
});

// useMatches()는 data router(createMemoryRouter/RouterProvider)에서만 동작하므로
// MemoryRouter + Routes 조합이 아니라 실제 router.tsx와 동일한 방식으로 렌더링한다.
// AppShellRoute가 useLogout(useQueryClient 필요)을 사용하므로 QueryClientProvider로 감싼다(#231).
function renderAt(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        element: <AppShellRoute />,
        children: [
          {
            path: '/dashboard',
            element: <div>대시보드 페이지</div>,
            handle: {
              breadcrumb: [{ label: '홈' }, { label: '대시보드' }],
              activeHref: '/dashboard',
            },
          },
          {
            path: '/no-handle',
            element: <div>핸들 없는 페이지</div>,
          },
          {
            path: MYPAGE_PLAN_ROUTE,
            element: <div>마이페이지 이용권 페이지</div>,
          },
          {
            path: '/inspections/:id/viewer',
            element: <div>분석 결과 뷰어 페이지</div>,
            handle: {
              breadcrumb: [{ label: '홈' }, { label: '점검 관리' }, { label: '분석 결과 뷰어' }],
              activeHref: '/inspections/1/viewer',
            },
          },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  );

  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('AppShellRoute', () => {
  it('현재 라우트 handle의 breadcrumb을 Header에 전달하고 페이지를 렌더링한다', () => {
    renderAt('/dashboard');

    expect(screen.getByText('홈')).not.toBeNull();
    expect(screen.getByText('대시보드 페이지')).not.toBeNull();
  });

  it('handle이 없는 라우트는 빈 breadcrumb으로 폴백하며 크래시하지 않는다', () => {
    renderAt('/no-handle');

    expect(screen.getByText('핸들 없는 페이지')).not.toBeNull();
  });

  it('router.tsx의 실제 isRouteImplemented를 SideNavBar까지 연결한다(미구현 경로 클릭 시 안내)', () => {
    renderAt('/dashboard');

    fireEvent.click(screen.getByText('통계'));

    expect(screen.getByRole('status').textContent).toBe('아직 구현되지 않은 페이지입니다');
  });

  it('프로필 클릭 시 MYPAGE_PLAN_ROUTE 상수 경로로 이동한다(#280 P3)', () => {
    renderAt('/dashboard');

    fireEvent.click(screen.getByRole('button', { name: '내 프로필' }));

    expect(screen.getByText('마이페이지 이용권 페이지')).not.toBeNull();
  });

  it('로그인 사용자의 role이 ADMIN이면 관리자 메뉴와 사이드바 프로필이 노출된다(HAJA-167, #184)', () => {
    useAuthStore.setState({ user: { ...baseUser, role: 'ADMIN' } });

    renderAt('/dashboard');

    expect(screen.getByText('관리자 페이지')).not.toBeNull();
    expect(screen.getByText('하자체크 담당자')).not.toBeNull();
  });

  it('로그인 사용자의 role이 ADMIN이 아니면 관리자 메뉴와 사이드바 프로필이 노출되지 않는다(HAJA-167, #184)', () => {
    useAuthStore.setState({ user: { ...baseUser, role: 'USER' } });

    renderAt('/dashboard');

    expect(screen.queryByText('관리자 페이지')).toBeNull();
    expect(screen.queryByText('하자체크 담당자')).toBeNull();
  });

  it('id=1이 아닌 동적 라우트로 진입해도 사이드바 "분석 결과 뷰어"가 활성 표시된다(#368)', () => {
    // activeHref는 handle(정적 선언)에서 오지 params가 아니라서, id 값과 무관하게
    // 이 라우트로 매치되면 항상 같은 activeHref가 SideNavBar에 전달된다.
    renderAt('/inspections/999/viewer');

    expect(screen.getByText('분석 결과 뷰어 페이지')).not.toBeNull();
    const link = screen.getByRole('link', { name: '분석 결과 뷰어' });
    expect(link.getAttribute('aria-current')).toBe('page');
  });

  it('로그인 사용자는 벨 배지에 미읽음 수가 표시되고 클릭 시 알림 패널이 열린다(HAJA-38)', async () => {
    useAuthStore.setState({ user: baseUser });

    renderAt('/dashboard');

    // 목 데이터 5건 중 미읽음 3건 — Header 벨 aria-label로 배지 반영을 확인
    expect(await screen.findByRole('button', { name: '알림 (미읽음 3건)' })).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '알림 (미읽음 3건)' }));

    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();
  });

  // PR머신 P3: 이전에는 동기적으로 aria-label만 확인해서 enabled=false든 true든 항상 통과했다(실제
  // GET /api/notifications 호출 여부를 검증하지 않음) — 호출 횟수를 스파이로 세어 0회임을 확인한다.
  it('미인증 상태에서는 알림을 조회하지 않아 벨에 미읽음 배지가 없다(HAJA-38, PR머신 P3)', async () => {
    let getNotificationsCallCount = 0;
    server.use(
      http.get('/api/notifications', () => {
        getNotificationsCallCount += 1;
        return HttpResponse.json({ success: true, data: [] });
      }),
    );

    renderAt('/dashboard');

    const bell = screen.getByRole('button', { name: '알림' });
    expect(bell.getAttribute('aria-label')).toBe('알림');

    // enabled=false인 useNotifications는 queryFn을 절대 호출하지 않아야 한다. waitFor의 즉시 통과와
    // "영원히 호출 안 됨"을 구분하기 위해 짧은 유예 뒤에도 여전히 0회인지 다시 확인한다.
    await waitFor(() => {
      expect(getNotificationsCallCount).toBe(0);
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(getNotificationsCallCount).toBe(0);
  });

  // react-reviewer P1-1: shared NotificationDropdown은 document mousedown으로 바깥 클릭을 감지해
  // onClose를 부른다. 벨 버튼은 그 rootRef 바깥이라, 패널이 열린 상태에서 벨을 다시 클릭하면
  // mousedown(→onClose)이 click(→토글)보다 먼저 발생 — 가드가 없으면 닫혔다가 곧바로 다시 열려버린다.
  // 실제 브라우저 이벤트 순서(mousedown → click)를 그대로 재현해 검증한다.
  it('패널이 열린 상태에서 벨을 다시 클릭하면(mousedown→click) 닫힌 채로 유지된다(react-reviewer P1-1)', async () => {
    useAuthStore.setState({ user: baseUser });

    renderAt('/dashboard');

    const bell = await screen.findByRole('button', { name: '알림 (미읽음 3건)' });
    fireEvent.click(bell);
    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();

    // 벨 재클릭의 실제 이벤트 시퀀스 — mousedown이 NotificationDropdown의 바깥클릭 감지를 먼저 트리거하고,
    // 뒤이어 click이 벨의 onNotificationClick을 트리거한다(userEvent.click도 내부적으로 이 순서로 발행).
    fireEvent.mouseDown(bell);
    fireEvent.click(bell);

    expect(screen.queryByRole('menu', { name: '알림' })).toBeNull();
  });

  // PR머신 P2(react-reviewer P1-1 수정의 부작용): 위 250ms 시간 가드는 onClose가 불리는 모든 경우를
  // 뭉뚱그려 막아서, 패널 밖 다른 요소를 클릭해 닫은 직후 벨을 눌러도 안 열리는 과잉 차단이 있었다.
  // "벨 자신에게 온 mousedown"만 좁혀서 표시하는 새 가드가 이 경우엔 정상 재오픈되는지 검증한다.
  it('패널 밖 다른 요소를 클릭해 닫힌 뒤 벨을 클릭하면 패널이 다시 열린다(PR머신 P2)', async () => {
    useAuthStore.setState({ user: baseUser });

    renderAt('/dashboard');

    const bell = await screen.findByRole('button', { name: '알림 (미읽음 3건)' });
    fireEvent.click(bell);
    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();

    // 패널 밖의 다른 요소(벨이 아님)를 mousedown+click — NotificationDropdown의 바깥클릭 감지로 닫힌다.
    const pageContent = screen.getByText('대시보드 페이지');
    fireEvent.mouseDown(pageContent);
    fireEvent.click(pageContent);
    expect(screen.queryByRole('menu', { name: '알림' })).toBeNull();

    // 곧바로 벨을 클릭하면 (구 가드처럼 무시되지 않고) 정상적으로 다시 열려야 한다.
    fireEvent.click(bell);
    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();
  });

  // 이슈 #474(PR #471 P2 후속): 우클릭·드래그아웃 등으로 벨에 mousedown만 발생하고 대응하는 click이
  // 뒤따르지 않으면(컨텍스트 메뉴가 뜨거나 mouseup이 벨 밖에서 일어남), 억제 플래그가 true로 고정된 채
  // 남아 그 다음 "정상" 클릭(자신의 mousedown+click 쌍을 갖춘 완전한 클릭)까지 삼켜버렸다.
  // 패널이 이미 닫힌 상태의 모든 mousedown에서 플래그를 무조건 리셋하도록 고쳐, 다음 정상 클릭이
  // 첫 시도에 바로 열리는지 검증한다.
  it('벨에 mousedown만 발생하고 click이 뒤따르지 않은 뒤에도, 다음 정상 클릭에서 패널이 바로 열린다(#474)', async () => {
    useAuthStore.setState({ user: baseUser });

    renderAt('/dashboard');

    const bell = await screen.findByRole('button', { name: '알림 (미읽음 3건)' });
    fireEvent.click(bell);
    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();

    // 우클릭 시나리오 재현: 벨에 mousedown만 발생(NotificationDropdown의 바깥클릭 감지로 먼저 닫힘),
    // 대응하는 click은 오지 않는다(컨텍스트 메뉴가 떴다고 가정).
    fireEvent.mouseDown(bell);
    expect(screen.queryByRole('menu', { name: '알림' })).toBeNull();

    // 뒤이은 완전한 정상 클릭(자신의 mousedown+click 쌍) — 이전에는 stale 플래그 때문에 무시되고
    // 한 번 더 클릭해야 열렸다. 첫 시도에 바로 열려야 한다.
    fireEvent.mouseDown(bell);
    fireEvent.click(bell);
    expect(await screen.findByRole('menu', { name: '알림' })).not.toBeNull();
  });
});
