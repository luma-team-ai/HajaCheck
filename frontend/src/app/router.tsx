// 라우트 정의는 이 파일 한 곳에 집중 — React_코드_컨벤션.md §7
// 경로: kebab-case / 인증 가드: ProtectedRoute, AdminRoute / 페이지 lazy loading 기본
import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import { ProtectedRoute } from '../shared/components/ProtectedRoute';

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
    path: '/dashboard',
    element: (
      <ProtectedRoute>
        <Suspense fallback={<div>불러오는 중...</div>}>
          <DashboardPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/dashboard (HAJA-17)
  {
    path: '/defects/:id',
    element: (
      <ProtectedRoute>
        <Suspense fallback={<div>불러오는 중...</div>}>
          <DefectDetailPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/defect (HAJA-171)
  {
    path: '/mypage/plan',
    element: (
      <ProtectedRoute>
        <Suspense fallback={<div>불러오는 중...</div>}>
          <MyPlanPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/mypage (HAJA-185, #212)
  // { path: '/facilities', ... }               — features/facility
  {
    path: '/map',
    element: (
      <ProtectedRoute>
        <Suspense fallback={<div>불러오는 중...</div>}>
          <MapPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/map (#28)
  // { path: '/defects', ... }                  — features/defect
  // { path: '/reports', ... }                  — features/report
  // { path: '/support', ... }                  — features/support
  // { path: '/admin/*', ... }                  — features/admin (AdminRoute)
]);
