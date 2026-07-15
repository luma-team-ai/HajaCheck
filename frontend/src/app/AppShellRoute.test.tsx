// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { AppShellRoute } from './AppShellRoute';

afterEach(cleanup);

// useMatches()는 data router(createMemoryRouter/RouterProvider)에서만 동작하므로
// MemoryRouter + Routes 조합이 아니라 실제 router.tsx와 동일한 방식으로 렌더링한다.
// AppShellRoute가 useLogout(useQueryClient 필요)을 사용하므로 QueryClientProvider로 감싼다(#231).
function renderAt(initialPath: string) {
  const queryClient = new QueryClient();
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
});
