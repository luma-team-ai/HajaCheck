import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../features/auth/store/authStore';
import { DASHBOARD_ROUTE, LOGIN_ROUTE } from '../constants/routes';
import type { Role } from '../constants/roles';

type Props = {
  // 미지정 시 중첩 라우트(Outlet) 렌더 — router.tsx는 AppShell 부모 라우트를 감싸는 방식(children 없음)과
  // 셸 밖 개별 업무 라우트를 감싸는 방식(children 있음)을 함께 사용한다(React_코드_컨벤션.md §7).
  children?: ReactNode;
  // 지정 시 인증에 더해 role까지 검사한다(미지정 = 인증만, 기존 라우트 동작 불변).
  // 비어 있지 않은 튜플 타입 — []는 "아무도 통과 못 함"이 되어 화면을 통째로 잠그는데, 그건 가드를
  // 다는 의도가 아니다(동적으로 만든 배열이 빈 값이 되는 실수를 컴파일 단계에서 막는다).
  // 관리자 화면은 이 prop을 직접 쓰지 말고 AdminRoute를 사용한다(컨벤션 §7).
  allowedRoles?: [Role, ...Role[]];
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

  // 권한 부족은 인증 실패와 다르게 다룬다 — /login으로 보내면 이미 로그인한 사용자가 로그인 화면을
  // 다시 보게 되고(혼란), 복귀 후 같은 경로로 돌아와 리다이렉트가 반복된다. 대시보드로 되돌린다.
  //
  // 사유 안내("접근 권한이 없습니다")는 여기서 하지 않는다 — 안내를 띄우려면 리다이렉트 대상인
  // features/dashboard가 state를 읽어야 하는데 그쪽은 다른 담당자 소유 영역이다
  // (React_코드_컨벤션.md §1 "features/ — 담당자 소유 영역"). 후속 이슈로 분리.
  //
  // 이 가드는 UX용이며 실제 권한 차단은 백엔드 엔드포인트 책임이다(프론트 값은 위조 가능).
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <Navigate to={DASHBOARD_ROUTE} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
