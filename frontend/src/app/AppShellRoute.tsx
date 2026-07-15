// 로그인 후 내부 페이지 공통 앱 셸(AppLayout) 라우트 래퍼 — router.tsx의 pathless 부모 route로 연결(HAJA-186, #217 후속)
// 목적: AppLayout 연결을 페이지 작성자의 자발적 opt-in이 아니라 라우터 레벨에서 강제한다.
// 새 페이지를 이 셸에 포함하려면 router.tsx의 children 배열에 라우트를 추가하고,
// 그 라우트의 `handle`에 breadcrumb/activeHref를 선언하기만 하면 된다 — 페이지 컴포넌트 자체는
// AppLayout을 몰라도 됨(react-router v6 표준 패턴: useMatches() + handle).
import { Outlet, useMatches } from 'react-router-dom';
import type { BreadcrumbItem } from '../shared/components/Header';
import { AppLayout } from '../shared/components/AppLayout';

export interface AppShellHandle {
  /** Header 브레드크럼(현재 위치) */
  breadcrumb: BreadcrumbItem[];
  /**
   * SideNavBar 활성 항목 경로. 미지정 시 AppLayout이 현재 URL(useLocation) 기준으로 계산.
   * 실제 라우트가 SideNavBar 메뉴 href와 다른 페이지(예: /defects/:id → /defects/detail)는
   * 명시적으로 지정해 해당 메뉴가 강조되도록 한다.
   */
  activeHref?: string;
}

function hasAppShellHandle(handle: unknown): handle is AppShellHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    'breadcrumb' in handle &&
    Array.isArray((handle as { breadcrumb: unknown }).breadcrumb)
  );
}

export function AppShellRoute() {
  const matches = useMatches();
  // 가장 깊은(마지막) match부터 breadcrumb/activeHref를 선언한 handle을 찾는다.
  const current = [...matches].reverse().find((match) => hasAppShellHandle(match.handle));
  const handle = current?.handle as AppShellHandle | undefined;

  return (
    <AppLayout breadcrumb={handle?.breadcrumb ?? []} activeHref={handle?.activeHref}>
      <Outlet />
    </AppLayout>
  );
}
