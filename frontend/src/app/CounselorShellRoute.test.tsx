// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../features/auth/store/authStore';
import type { User } from '../features/auth/types';
import { CounselorShellRoute } from './CounselorShellRoute';

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

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null });
});

// useMatches()는 data router에서만 동작하므로 PlatformAdminShellRoute.test.tsx와 동일하게
// createMemoryRouter/RouterProvider로 렌더한다. useLogout이 useQueryClient를 쓰므로 QueryClientProvider로 감싼다.
function renderAt(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        element: <CounselorShellRoute />,
        children: [
          {
            path: '/counsel-console/queue',
            element: <div>대기열 페이지</div>,
            handle: {
              breadcrumb: [{ label: '상담원 콘솔' }, { label: '상담 대기열' }],
              activeHref: '/counsel-console/queue',
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

describe('CounselorShellRoute', () => {
  it('breadcrumb과 페이지 콘텐츠를 렌더하고, 상담 대기열 메뉴만 노출한다(일반 DEFAULT_ITEMS 없음)', () => {
    useAuthStore.setState({ user: counselorUser });

    renderAt('/counsel-console/queue');

    expect(screen.getAllByText('상담원 콘솔').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('대기열 페이지')).not.toBeNull();
    expect(screen.getAllByText('상담 대기열').length).toBeGreaterThanOrEqual(1);
    // 일반 셸의 대시보드/시설물 관리 등은 노출되지 않는다.
    expect(screen.queryByText('대시보드')).toBeNull();
    expect(screen.queryByText('시설물 관리')).toBeNull();
    // "ADMIN" 배지는 플랫폼 관리자 콘솔 전용(SideNavBar isAdmin 하드코딩) — 상담원은 관리자가 아니므로 미노출.
    expect(screen.queryByText('ADMIN')).toBeNull();
  });

  it('로고 클릭 시 /dashboard가 아니라 상담 대기열(/counsel-console/queue)로 이동한다(brandHref override)', () => {
    useAuthStore.setState({ user: counselorUser });

    renderAt('/counsel-console/queue');

    const logoLink = screen.getByLabelText('HajaCheck 홈으로 이동');
    expect(logoLink.getAttribute('href')).toBe('/counsel-console/queue');
  });

  it('헤더 프로필 버튼 클릭 시 드롭다운이 열리고 이름·이메일·로그아웃이 노출된다', () => {
    useAuthStore.setState({ user: counselorUser });

    renderAt('/counsel-console/queue');

    fireEvent.click(screen.getByRole('button', { name: '내 프로필' }));

    expect(screen.getByText('counselor@example.com')).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: /로그아웃/ })).not.toBeNull();
  });
});
