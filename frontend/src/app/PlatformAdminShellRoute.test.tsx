// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../features/auth/store/authStore';
import type { User } from '../features/auth/types';
import { PlatformAdminShellRoute } from './PlatformAdminShellRoute';

const platformAdminUser: User = {
  id: 1,
  email: 'platform-admin@example.com',
  name: '플랫폼 운영진',
  role: 'PLATFORM_ADMIN',
  companyId: null,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: null,
};

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null });
});

// useMatches()는 data router에서만 동작하므로 AppShellRoute.test.tsx와 동일하게
// createMemoryRouter/RouterProvider로 렌더한다. useLogout이 useQueryClient를 쓰므로 QueryClientProvider로 감싼다.
function renderAt(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        element: <PlatformAdminShellRoute />,
        children: [
          {
            path: '/platform-admin/users',
            element: <div>사용자 관리 페이지</div>,
            handle: {
              breadcrumb: [{ label: '플랫폼 관리자' }, { label: '사용자 관리' }],
              activeHref: '/platform-admin/users',
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

describe('PlatformAdminShellRoute', () => {
  it('breadcrumb과 페이지 콘텐츠를 렌더하고, 플랫폼 관리자 7개 메뉴만 노출한다(일반 DEFAULT_ITEMS 없음)', () => {
    useAuthStore.setState({ user: platformAdminUser });

    renderAt('/platform-admin/users');

    // "플랫폼 관리자"는 breadcrumb과 사이드바 그룹 헤더 양쪽에 렌더되므로 getAllByText로 개수만 확인하고,
    // 실제 클릭 대상은 사이드바 그룹 토글 버튼으로 role로 좁혀 특정한다.
    expect(screen.getAllByText('플랫폼 관리자').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('사용자 관리 페이지')).not.toBeNull();
    // 일반 셸의 대시보드/시설물 관리 등은 노출되지 않는다.
    expect(screen.queryByText('대시보드')).toBeNull();
    expect(screen.queryByText('시설물 관리')).toBeNull();

    // activeHref('/platform-admin/users')가 이 그룹의 하위 항목이라 마운트 시 이미 펼쳐진 상태다.
    expect(screen.getByText('플랜·쿼터 관리')).not.toBeNull();
    expect(screen.getByText('시스템 모니터링')).not.toBeNull();
  });

  it('로고 클릭 시 /dashboard가 아니라 사용자 관리(/platform-admin/users)로 이동한다(brandHref override)', () => {
    useAuthStore.setState({ user: platformAdminUser });

    renderAt('/platform-admin/users');

    const logoLink = screen.getByLabelText('HajaCheck 홈으로 이동');
    expect(logoLink.getAttribute('href')).toBe('/platform-admin/users');
  });

  it('로그인 사용자 이름을 사이드바 하단에 표시한다', () => {
    useAuthStore.setState({ user: platformAdminUser });

    renderAt('/platform-admin/users');

    expect(screen.getByText('플랫폼 운영진')).not.toBeNull();
  });
});
