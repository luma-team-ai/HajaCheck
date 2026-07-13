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

// 개발 확인용 임시 라우트 — 이슈 #116 공통 컴포넌트 시각 검증 목적, 머지 전 유지 여부 협의 필요
const ComponentShowcasePage = lazy(() => import('../features/dev-showcase/ComponentShowcasePage'));

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
    // TODO(#116): 공통 컴포넌트 시각 검증용 임시 라우트 — 팀 리뷰 후 제거 또는 정식 스토리북 대체 검토
    path: '/dev/components',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <ComponentShowcasePage />
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
  // { path: '/login', ... }                    — features/auth
  // { path: '/dashboard', ... }                — features/dashboard
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
