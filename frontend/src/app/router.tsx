// 라우트 정의는 이 파일 한 곳에 집중 — React_코드_컨벤션.md §7
// 경로: kebab-case / 인증 가드: ProtectedRoute, AdminRoute / 페이지 lazy loading 기본
import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import { AppShellRoute } from './AppShellRoute';

const MapPage = lazy(() => import('../features/map/MapPage'));
const ResultViewerPage = lazy(() =>
  import('../features/inspection/pages/ResultViewerPage').then((m) => ({
    default: m.ResultViewerPage,
  })),
);

const LandingPage = lazy(() => import('../features/landing/LandingPage'));

const LoginPage = lazy(() =>
  import('../features/auth/pages/LoginPage').then((m) => ({ default: m.LoginPage })),
);

// 기업 인증 플로우 — HAJA-170(#187)
const CompanySignupPage = lazy(() =>
  import('../features/auth/pages/CompanySignupPage').then((m) => ({
    default: m.CompanySignupPage,
  })),
);
const CompanySignupPendingPage = lazy(() =>
  import('../features/auth/pages/CompanySignupPendingPage').then((m) => ({
    default: m.CompanySignupPendingPage,
  })),
);
const FindIdPage = lazy(() =>
  import('../features/auth/pages/FindIdPage').then((m) => ({ default: m.FindIdPage })),
);
// 비밀번호 찾기·재설정 2화면은 계정 탈취 P1(보안 리뷰)로 이번 범위에서 제외 — 보안질문 방식으로 후속(#194, HAJA-172)

const DashboardPage = lazy(() =>
  import('../features/dashboard/pages/DashboardPage').then((m) => ({
    default: m.DashboardPage,
  })),
);

const DefectDetailPage = lazy(() =>
  import('../features/defect/pages/DefectDetailPage').then((m) => ({
    default: m.DefectDetailPage,
  })),
);

// 마이페이지 — 내 플랜 (HAJA-185, #212)
const MyPlanPage = lazy(() =>
  import('../features/mypage/pages/MyPlanPage').then((m) => ({ default: m.MyPlanPage })),
);

const FacilityListPage = lazy(() =>
  import('../features/facility/pages/FacilityListPage').then((m) => ({
    default: m.FacilityListPage,
  })),
);

export const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <LandingPage />
      </Suspense>
    ),
  },
  {
    path: '/inspections/:id/viewer',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <ResultViewerPage />
      </Suspense>
    ),
  },
  {
    path: '/login',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <LoginPage />
      </Suspense>
    ),
  }, // — features/auth (HAJA-160, #157)
  {
    path: '/signup/company',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <CompanySignupPage />
      </Suspense>
    ),
  },
  {
    path: '/signup/company/pending',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <CompanySignupPendingPage />
      </Suspense>
    ),
  },
  {
    path: '/find-id',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <FindIdPage />
      </Suspense>
    ),
  }, // — features/auth 기업 인증 플로우 (HAJA-170, #187)
  {
    // 로그인 후 내부 페이지 공통 앱 셸(SideNavBar+Header, AppLayout) — nested route로 강제 연결(HAJA-186, #217 후속).
    // 이 셸에 새 페이지를 포함하려면: children에 라우트 추가 + handle에 breadcrumb/activeHref 선언만 하면 됨
    // (페이지 컴포넌트는 AppLayout을 직접 감쌀 필요 없음 — AppShellRoute.tsx 참조)
    element: <AppShellRoute />,
    children: [
      {
        // TODO: 인증 가드(ProtectedRoute) 도입 시 시설물 현황·점검 통계 등 업무 데이터 노출 라우트이므로 적용 필요 — 현재는 인증 스켈레톤(features/auth) 미구현이라 미적용(의도된 임시 상태)
        path: '/dashboard',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <DashboardPage />
          </Suspense>
        ),
        handle: { breadcrumb: [{ label: '홈' }, { label: '대시보드' }], activeHref: '/dashboard' },
      }, // — features/dashboard (HAJA-17)
      {
        // TODO: 인증 가드(ProtectedRoute) 도입 시 하자 상세(업무 데이터) 노출 라우트이므로 적용 필요 — 현재 라우터에 가드 미적용(ProtectedRoute 컴포넌트 자체가 아직 없음)
        path: '/defects/:id',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <DefectDetailPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '하자 관리' }, { label: '하자 상세' }],
          activeHref: '/defects/detail',
        },
      }, // — features/defect (HAJA-171)
      {
        // TODO: 인증 가드(ProtectedRoute) 도입 시 적용 필요 — 현재 라우터에 가드 미적용(다른 대시보드 셸 라우트와 동일 상태)
        path: '/mypage/plan',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <MyPlanPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '마이페이지' }, { label: '내 플랜' }],
          activeHref: '/mypage/plan',
        },
      }, // — features/mypage (HAJA-185, #212)
    ],
  },
  {
    // TODO: 인증 가드(ProtectedRoute) 도입 시 시설물 목록(업무 데이터) 노출 라우트이므로 적용 필요 — 현재는 라우터 스켈레톤 단계라 미적용
    path: '/facilities',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <FacilityListPage />
      </Suspense>
    ),
  }, // — features/facility (dev-04-01, FR-003)
  {
    // TODO: 인증 가드(ProtectedRoute) 도입 시 시설물 위치 노출 라우트이므로 적용 필요 — 현재는 라우터 스켈레톤 단계라 미적용
    path: '/map',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <MapPage />
      </Suspense>
    ),
  }, // — features/map (#28)
  // { path: '/defects', ... }                  — features/defect
  // { path: '/reports', ... }                  — features/report
  // { path: '/support', ... }                  — features/support
  // { path: '/admin/*', ... }                  — features/admin (AdminRoute)
]);
