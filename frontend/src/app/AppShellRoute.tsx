// 로그인 후 내부 페이지 공통 앱 셸(AppLayout) 라우트 래퍼 — router.tsx의 pathless 부모 route로 연결(HAJA-186, #217 후속)
// 목적: AppLayout 연결을 페이지 작성자의 자발적 opt-in이 아니라 라우터 레벨에서 강제한다.
// 새 페이지를 이 셸에 포함하려면 router.tsx의 children 배열에 라우트를 추가하고,
// 그 라우트의 `handle`에 breadcrumb/activeHref를 선언하기만 하면 된다 — 페이지 컴포넌트 자체는
// AppLayout을 몰라도 됨(react-router v6 표준 패턴: useMatches() + handle).
import { useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Outlet, useMatches, useNavigate } from 'react-router-dom';
import { useLogout } from '../features/auth/hooks/useLogout';
import { MYPAGE_PLAN_ROUTE, MYPAGE_PROFILE_ROUTE } from '../features/auth/constants';
import { useAuthStore } from '../features/auth/store/authStore';
import { NotificationCenter } from '../features/notification/components/NotificationCenter';
import { useNotifications } from '../features/notification/hooks/useNotifications';
import { useMyPlan } from '../features/mypage/hooks/useMyPlan';
import { PLAN_NAME_LABEL } from '../features/mypage/utils/planFormat';
import type { BreadcrumbItem } from '../shared/components/Header';
import { AppLayout } from '../shared/components/AppLayout';
import { isAdminRole } from '../shared/constants/roles';
import { isRouteImplemented } from './implementedRoutes';

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
  const navigate = useNavigate();
  const authUser = useAuthStore((state) => state.user);
  // 관리자 메뉴/사이드바 프로필 노출 여부 — role 기반(HAJA-167, #184).
  // AppLayout이 isAdmin일 때만 SideNavBar에 user를 전달하도록 내부에서 필터링한다.
  // AdminRoute(shared/components/AdminRoute.tsx)의 실제 접근 차단과 같은 기준(isAdminRole)을 쓴다
  // — 각자 role === 'ADMIN'을 따로 비교하면 한쪽만 바뀌었을 때 메뉴·접근 판정이 어긋난다(#378).
  const isAdmin = isAdminRole(authUser?.role);
  const { logout } = useLogout();
  // Header 프로필 드롭다운(HAJA-758) 상단 플랜 뱃지용 — 마이페이지 "내 플랜"과 동일 소스(useMyPlan)를 재사용해
  // 표기가 어긋나지 않게 한다. 백엔드 미배포 시에도 useMyPlan 자체가 예제 데이터로 폴백한다(HAJA-185).
  const { data: myPlan } = useMyPlan();
  // 가장 깊은(마지막) match부터 breadcrumb/activeHref를 선언한 handle을 찾는다.
  const current = [...matches].reverse().find((match) => hasAppShellHandle(match.handle));
  const handle = current?.handle as AppShellHandle | undefined;

  // 알림 센터(HAJA-38) — Header 벨 버튼은 AppLayout 내부(shared, 미터치)라 열림 상태와 unreadCount는
  // 이 통합지점(app/)이 들고 NotificationCenter(컨테이너)에 boolean으로만 내려준다.
  // useNotifications는 NotificationCenter 안에서도 같은 쿼리 키로 호출되므로 TanStack Query 캐시가
  // 공유되어 벨 배지용으로 별도 네트워크 요청이 추가되지는 않는다.
  const isAuthenticated = Boolean(authUser);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const { data: notifications } = useNotifications(isAuthenticated);
  const unreadCount = notifications?.filter((item) => !item.isRead).length ?? 0;

  // 벨 재클릭 토글 경합(react-reviewer P1, PR머신 P2로 범위 좁힘) 가드: shared NotificationDropdown은
  // document mousedown으로 바깥 클릭을 감지해 onClose를 부른다. 벨 버튼은 그 rootRef 바깥이라, 패널이
  // 열린 상태에서 벨을 다시 클릭하면 mousedown(→onClose로 닫힘)이 먼저, click(→토글로 재오픈)이 그
  // 다음 순서로 적용돼 결국 안 닫힌다.
  // (구) 시간 기반 250ms 가드는 onClose가 불리는 모든 경우(ESC·패널 밖 다른 요소 클릭 포함)를 뭉뚱그려
  // 막아버려서, ESC로 닫거나 다른 요소를 클릭해 닫은 직후 벨을 눌러도 안 열리는 과잉 차단 버그가
  // 있었다(PR머신 P2). → "패널이 열려 있는 동안 벨 자신에게 mousedown이 온 경우"만 좁혀서 표시한다.
  // Header 벨 버튼은 shared(미터치)라 onMouseDown prop을 못 받으므로, AppLayout을 감싸는 아래
  // 래퍼의 capture 단계에서 이벤트 대상을 aria-label로 식별한다(Header.tsx: aria-label={unreadCount
  // > 0 ? `알림 (미읽음 ${unreadCount}건)` : '알림'} — '알림' 접두는 Header 내 벨 버튼에만 쓰인다).
  // PR머신 P2(이슈 #474): 위 가드는 "mousedown 이후 반드시 벨에서 click이 뒤따른다"고 가정했다.
  // 우클릭(mousedown만 발생, click 대신 컨텍스트 메뉴)이나 벨을 누른 채 커서를 밖으로 빼서 뗀 경우
  // (mouseup이 벨 밖이라 click이 벨에 도달하지 않음)에는 대응하는 click이 오지 않아 플래그가 true로
  // 고정된 채 남고, 그 다음 정상 클릭까지 삼켜버렸다. → "소비 시에만 리셋"이 아니라 패널이 닫힌 상태의
  // mousedown마다 매번 최신값으로 덮어써(닫혀 있으면 무조건 false) 이전 mousedown의 결과가 새지 않게 한다.
  const suppressNextBellClickRef = useRef(false);
  const handleNotificationClose = () => {
    setNotificationOpen(false);
  };
  const handleNotificationClick = () => {
    if (suppressNextBellClickRef.current) {
      suppressNextBellClickRef.current = false;
      return;
    }
    setNotificationOpen((prev) => !prev);
  };
  const handleShellMouseDownCapture = (event: ReactMouseEvent<HTMLDivElement>) => {
    if (!notificationOpen) {
      suppressNextBellClickRef.current = false;
      return;
    }
    const target = event.target as Element | null;
    suppressNextBellClickRef.current = Boolean(target?.closest('button[aria-label^="알림"]'));
  };

  return (
    <div onMouseDownCapture={handleShellMouseDownCapture}>
      <AppLayout
        breadcrumb={handle?.breadcrumb ?? []}
        activeHref={handle?.activeHref}
        isRouteImplemented={isRouteImplemented}
        isAdmin={isAdmin}
        user={
          authUser
            ? { name: authUser.name, avatarUrl: authUser.profileImageUrl ?? undefined }
            : undefined
        }
        onLogout={() => void logout()}
        onProfileClick={() => navigate(MYPAGE_PLAN_ROUTE)}
        profileMenu={
          authUser
            ? {
                companyName: authUser.companyName ?? '개인 회원',
                planLabel: myPlan ? PLAN_NAME_LABEL[myPlan.plan.name] : PLAN_NAME_LABEL.FREE,
                name: authUser.name,
                email: authUser.email,
                onMyInfoClick: () => navigate(MYPAGE_PROFILE_ROUTE),
                onMyPlanClick: () => navigate(MYPAGE_PLAN_ROUTE),
                onLogout: () => void logout(),
              }
            : undefined
        }
        unreadCount={unreadCount}
        onNotificationClick={handleNotificationClick}
      >
        <Outlet />
      </AppLayout>
      <NotificationCenter open={notificationOpen} onClose={handleNotificationClose} enabled={isAuthenticated} />
    </div>
  );
}
