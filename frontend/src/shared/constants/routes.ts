// shared/components/ProtectedRoute가 리다이렉트 대상으로 쓰는 라우트 경로.
// 두 상수를 한곳에 모아둔다 — 같은 가드에서 한쪽은 features/auth, 한쪽은 shared를 참조하면
// 세 번째 대상을 추가할 때 어디에 둘지 기준이 사라진다.
// features/auth/constants.ts는 이 값을 재export해 auth 플로우 내부 사용처(7곳)의 import 경로를 유지한다.
//
// 주의: 서비스되는 로그인 경로 문자열은 shared/constants/authPaths.ts의 LOGIN_PATH에도 있다.
// 그쪽은 axios 401 인터셉터가 window.location으로 쓰는 값이라 vite base(BASE_URL)가 반영된 절대경로고,
// 여기 LOGIN_ROUTE는 react-router 내부 경로(basename이 라우터에서 따로 붙는다)라 형태가 다르다.
// basename 배포 설정을 바꾼다면 두 파일을 함께 확인할 것.

// role 판정 함수(isPlatformAdminRole 등)를 쓰지 않고 Role 유니온을 직접 switch 한다 —
// 판정 함수 조합은 "빠뜨린 role"을 컴파일러가 알 수 없어 전수성을 강제하지 못한다(아래 주석 참조).
import type { Role } from './roles';

/** 미인증 시 리다이렉트 대상(react-router 경로) */
export const LOGIN_ROUTE = '/login';

/** 인증은 됐으나 권한이 없을 때(AdminRoute·ProtectedRoute allowedRoles 불충족) 되돌려보낼 기본 화면 */
export const DASHBOARD_ROUTE = '/dashboard';

/** 랜딩(홈) — 로그인 화면 로고 클릭 등 공개 진입점(#421). router.tsx 루트 경로와 일치 */
export const LANDING_ROUTE = '/';

// 플랫폼 관리자 콘솔(#535) — 기업회원 로그인(LOGIN_ROUTE)과 분리된 전용 로그인 경로.
// PlatformAdminRoute가 미인증 시 이 값으로 리다이렉트한다.
export const PLATFORM_ADMIN_LOGIN_ROUTE = '/platform-admin/login';

/** 플랫폼 관리자 콘솔 진입점 — 로그인 성공 후 이동 대상(router.tsx에서 첫 메뉴로 재리다이렉트) */
export const PLATFORM_ADMIN_ROUTE = '/platform-admin';

// 초대 코드 입력(#799, #794) — 소셜 최초 로그인 직후 status=WAITING(company_id 없음)인 사용자가
// 발급받은 초대 코드로 회사에 연결하는 화면. ProtectedRoute가 WAITING 사용자를 여기로 강제
// 리다이렉트해야 하므로(보호 라우트 어디로 가든 동일하게 가로채야 함) shared 상수로 둔다.
export const INVITE_CODE_ROUTE = '/invite-code';

// 토스페이먼츠 결제창 연동(#989, HAJA-490) — 결제창(외부 페이지)에서 돌아오는 successUrl/failUrl
// 리다이렉트 대상. features/mypage의 useRequestTossPayment 훅이 절대 URL을 조립할 때도 이 값을
// 공유한다(하드코딩 금지 방지 — router.tsx와 동일 문자열을 두 곳에서 따로 관리하면 드리프트 위험).
export const PAYMENT_SUCCESS_ROUTE = '/payments/success';
export const PAYMENT_FAIL_ROUTE = '/payments/fail';

// 상담원 콘솔(#1001, HAJA-495) — COUNSELOR 전용 대기열 화면. 로그인 후 role=COUNSELOR 리다이렉트
// (useCounselorLogin.ts/CounselorLoginPage.tsx)와 CounselorRoute 권한 부족 리다이렉트가 같은 값을
// 참조하도록 PLATFORM_ADMIN_ROUTE와 동일한 이유로 shared 상수로 둔다.
export const COUNSELOR_QUEUE_ROUTE = '/counsel-console/queue';

// 상담원 전용 로그인(플랫폼 관리자 로그인과 동일 디자인, 라벨만 "상담원 로그인") — 기업회원 로그인
// (LOGIN_ROUTE)과 분리된 전용 경로. CounselorRoute가 미인증 시 이 값으로 리다이렉트한다.
export const COUNSELOR_LOGIN_ROUTE = '/counsel-console/login';

// role별 "홈"(#1513) — 로그인한 사용자를 어디로 보내야 하는지의 단일 기준.
// 두 사용처가 반드시 같은 답을 내야 한다:
//   1) ProtectedRoute — allowedRoles 불충족 시 되돌려보낼 화면
//   2) LoginPage — 이미 세션이 있는 채로 /login에 재방문했을 때의 기본 목적지
// 서로 다른 답을 내면 무한 리다이렉트가 된다. 예: 기업 대시보드(AppShell)에 allowedRoles가
// 걸린 뒤 거부 대상을 DASHBOARD_ROUTE로 고정해 두면 거부 → 대시보드 → 거부 …로 순환한다.
//
// ⚠️ 불변식: **각 role의 홈은 그 role이 통과할 수 있는 가드 뒤에 있어야 한다.**
// (PLATFORM_ADMIN→PlatformAdminRoute / COUNSELOR→CounselorRoute / 나머지→AppShell ProtectedRoute
//  allowedRoles=COMPANY_DASHBOARD_ROLES). 이 PR로 /dashboard 자체가 잠겼기 때문에, 폴백으로
// DASHBOARD_ROUTE를 받는 role이 COMPANY_DASHBOARD_ROLES에 없으면 "거부 → /dashboard → 거부"가 되어
// Navigate가 같은 경로를 replace 하며 아무것도 렌더하지 않는 **백지 데드엔드**가 된다.
//
// 그래서 이 불변식은 주석으로 "서술"하지 않고 아래 switch가 **강제**한다:
//   ① 모든 Role을 명시 case로 나열하고 default에서 never 할당 → Role 유니온에 값이 추가되면
//      그 값을 여기에 배치하기 전까지 **컴파일 에러**가 난다(백엔드 AuthControllerPortalRolesTest의
//      role 전수성 강제와 같은 역할).
//   ② DASHBOARD_ROUTE로 폴백하는 role 목록(ADMIN/INSPECTOR/USER)이 COMPANY_DASHBOARD_ROLES와
//      일치하는지는 shared/constants/roleHome.test.ts가 전수 단언한다(값을 지워도 잡히도록).
// 새 role을 추가할 때는 반드시 "그 role이 통과할 수 있는 화면"을 홈으로 지정할 것 —
// 통과 못 하는 화면을 적으면 위 백지 데드엔드가 재현된다.
export function resolveRoleHomeRoute(role: Role | undefined): string {
  switch (role) {
    case 'PLATFORM_ADMIN':
      return PLATFORM_ADMIN_ROUTE;
    case 'COUNSELOR':
      return COUNSELOR_QUEUE_ROUTE;
    // 기업 대시보드(AppShell)를 통과하는 role + role 미상(undefined, 가드 진입 전 호출)
    case 'ADMIN':
    case 'INSPECTOR':
    case 'USER':
    case undefined:
      return DASHBOARD_ROUTE;
    default: {
      // 도달 불가(위에서 Role 유니온을 전수 처리) — 유니온에 값이 추가되면 여기서 컴파일이 깨진다.
      // 런타임에 여기 오는 경우는 서버가 프론트 유니온에 없는 role을 내려준 때뿐이며, 그때는
      // 어느 콘솔도 통과할 수 없으므로 기본 화면으로 보낸다(가드가 다시 막으면 화면이 비지만,
      // 그 상태는 이미 프론트-서버 role 체계가 어긋난 배포 사고다 — 위 컴파일 게이트가 1차 방어).
      const _exhaustive: never = role;
      void _exhaustive;
      return DASHBOARD_ROUTE;
    }
  }
}
