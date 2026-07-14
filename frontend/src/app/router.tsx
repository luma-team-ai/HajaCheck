// 라우트 정의는 이 파일 한 곳에 집중 — React_코드_컨벤션.md §7
// 경로: kebab-case / 인증 가드: ProtectedRoute, AdminRoute / 페이지 lazy loading 기본
import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';

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
const FindPasswordPage = lazy(() =>
  import('../features/auth/pages/FindPasswordPage').then((m) => ({
    default: m.FindPasswordPage,
  })),
);
const ResetPasswordPage = lazy(() =>
  import('../features/auth/pages/ResetPasswordPage').then((m) => ({
    default: m.ResetPasswordPage,
  })),
);

const DashboardPage = lazy(() =>
  import('../features/dashboard/pages/DashboardPage').then((m) => ({
    default: m.DashboardPage,
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
  },
  {
    path: '/find-password',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <FindPasswordPage />
      </Suspense>
    ),
  },
  {
    path: '/reset-password',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <ResetPasswordPage />
      </Suspense>
    ),
  }, // — features/auth 기업 인증 플로우 (HAJA-170, #187)
  {
    // TODO: 인증 가드(ProtectedRoute) 도입 시 시설물 현황·점검 통계 등 업무 데이터 노출 라우트이므로 적용 필요 — 현재는 인증 스켈레톤(features/auth) 미구현이라 미적용(의도된 임시 상태)
    path: '/dashboard',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <DashboardPage />
      </Suspense>
    ),
  }, // — features/dashboard (HAJA-17)
  // { path: '/facilities', ... }               — features/facility
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
