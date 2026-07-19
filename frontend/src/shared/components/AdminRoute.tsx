import type { ReactNode } from 'react';
import { ProtectedRoute } from './ProtectedRoute';

type Props = {
  children?: ReactNode;
};

// 관리자 가드 — React_코드_컨벤션.md §7 "인증 가드: ProtectedRoute(로그인), AdminRoute(관리자)
// 공통 컴포넌트로 처리". 관리자 화면은 이 컴포넌트로만 감싼다(#378, 이후 #21 하위 관리자 화면 공통).
//
// 판정 기준은 app/AppShellRoute.tsx의 isAdmin과 같아야 한다 — 사이드바에 메뉴가 보이는 사용자와
// 실제로 들어갈 수 있는 사용자가 어긋나면, 메뉴를 눌렀는데 튕기는 화면이 된다. 두 곳 모두
// shared/constants/roles.ts의 isAdminRole을 기준으로 삼는다(AppShellRoute는 직접 호출, 여기는
// ProtectedRoute의 allowedRoles 튜플 형태상 'ADMIN' 리터럴로 표현).
//
// 실제 권한 차단은 백엔드 엔드포인트 책임이다(스토어의 role은 클라이언트 값이라 위조 가능).
// 이 가드는 잘못된 화면을 보여주지 않기 위한 UX 장치다.
export function AdminRoute({ children }: Props) {
  return <ProtectedRoute allowedRoles={['ADMIN']}>{children}</ProtectedRoute>;
}
