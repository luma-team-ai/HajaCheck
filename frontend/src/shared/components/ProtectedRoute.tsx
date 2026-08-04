import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../features/auth/store/authStore';
import { INVITE_CODE_ROUTE, LOGIN_ROUTE, resolveRoleHomeRoute } from '../constants/routes';
import type { Role } from '../constants/roles';

type Props = {
  // 미지정 시 중첩 라우트(Outlet) 렌더 — router.tsx는 AppShell 부모 라우트를 감싸는 방식(children 없음)과
  // 셸 밖 개별 업무 라우트를 감싸는 방식(children 있음)을 함께 사용한다(React_코드_컨벤션.md §7).
  children?: ReactNode;
  // 지정 시 인증에 더해 role까지 검사한다(미지정 = 인증만, 기존 라우트 동작 불변).
  // 비어 있지 않은 튜플 타입 — []는 "아무도 통과 못 함"이 되어 화면을 통째로 잠그는데, 그건 가드를
  // 다는 의도가 아니다(동적으로 만든 배열이 빈 값이 되는 실수를 컴파일 단계에서 막는다).
  // 관리자 화면은 이 prop을 직접 쓰지 말고 AdminRoute를 사용한다(컨벤션 §7).
  // readonly — shared/constants/roles.ts의 공용 집합(COMPANY_DASHBOARD_ROLES)을 그대로 넘길 수 있게
  // 하면서, 호출부가 전달한 배열을 이 컴포넌트가 바꾸지 않음을 타입으로 못박는다.
  allowedRoles?: readonly [Role, ...Role[]];
};

// 인증 가드 — useAuthStore.user 미존재(미인증) 시 /login으로 리다이렉트.
// app/AuthGate.tsx가 앱 부트스트랩 시 getMe()로 authStore.user를 복원한 뒤에만 라우터(children)를
// 렌더하므로, 이 컴포넌트가 평가되는 시점의 user는 항상 복원이 끝난 authoritative 값이다
// (PR #232 P2-1 — 새로고침 직후 복원 전 오탐 리다이렉트 방지).
export function ProtectedRoute({ children, allowedRoles }: Props) {
  const user = useAuthStore((state) => state.user);
  const location = useLocation();

  if (!user) {
    // 로그인 성공 후 원래 목적지로 복귀할 수 있게 현재 경로를 state.from으로 보존(P3-2) —
    // LoginPage가 location.state?.from을 읽어 복귀, 없으면 기존대로 /dashboard
    return (
      <Navigate
        to={LOGIN_ROUTE}
        replace
        state={{ from: `${location.pathname}${location.search}` }}
      />
    );
  }

  // 초대 코드 미입력(status=WAITING, #794/#799) — company_id가 없어 대부분의 보호 리소스가 백엔드
  // SessionUserRevalidationFilter에서 403(AUTH_ACCOUNT_WAITING)으로 막힌다. 화면이 깨진 채로 뜨는
  // 대신 여기서 선제적으로 초대 코드 입력 화면으로 보낸다. 그 화면 자체는 무한 루프 방지를 위해 예외.
  if (user.status === 'WAITING' && location.pathname !== INVITE_CODE_ROUTE) {
    return <Navigate to={INVITE_CODE_ROUTE} replace />;
  }

  // status=SUSPENDED는 여기서 별도로 가로챌 필요가 없다(#816 P2 확인 완료) — 자체 로그인은
  // LockedException, 소셜 로그인은 CustomOAuth2UserService.requireActive가 정지 계정을 인증
  // 단계에서 차단하고, 로그인 후에도 SessionUserRevalidationFilter가 모든 요청(이 화면의
  // getMe 포함)을 401로 막아 axios 인터셉터가 즉시 /login으로 하드 리다이렉트한다. 즉
  // authStore.user가 SUSPENDED로 채워진 채 ProtectedRoute까지 도달하는 경로 자체가 없다
  // (WAITING과 달리 SessionUserRevalidationFilter가 세션을 유지하지 않고 즉시 끊기 때문).

  // 권한 부족은 인증 실패와 다르게 다룬다 — /login으로 보내면 이미 로그인한 사용자가 로그인 화면을
  // 다시 보게 되고(혼란), 복귀 후 같은 경로로 돌아와 리다이렉트가 반복된다. role별 홈으로 되돌린다.
  //
  // 되돌릴 곳을 DASHBOARD_ROUTE로 고정하지 않는 이유(#1513): 기업 대시보드(AppShell) 자체에
  // allowedRoles가 걸리면서, 거부된 PLATFORM_ADMIN/COUNSELOR를 대시보드로 보내면 그 대시보드가
  // 다시 거부해 무한 리다이렉트가 된다. resolveRoleHomeRoute는 각 role이 통과할 수 있는 화면을
  // 돌려주므로 리다이렉트가 한 번에 정착한다(routes.ts 주석 참조).
  // ADMIN/INSPECTOR/USER의 홈은 여전히 DASHBOARD_ROUTE라 기존 AdminRoute 동작은 그대로다.
  //
  // 사유 안내("접근 권한이 없습니다")는 여기서 하지 않는다 — 안내를 띄우려면 리다이렉트 대상인
  // features/dashboard가 state를 읽어야 하는데 그쪽은 다른 담당자 소유 영역이다
  // (React_코드_컨벤션.md §1 "features/ — 담당자 소유 영역"). 후속 이슈로 분리.
  //
  // 이 가드는 UX용이며 실제 권한 차단은 백엔드 엔드포인트 책임이다(프론트 값은 위조 가능).
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <Navigate to={resolveRoleHomeRoute(user.role)} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
