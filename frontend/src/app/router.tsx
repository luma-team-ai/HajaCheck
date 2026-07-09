// 라우트 정의는 이 파일 한 곳에 집중 — React_코드_컨벤션.md §7
// 경로: kebab-case / 인증 가드: ProtectedRoute, AdminRoute / 페이지 lazy loading 기본
import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';

const MapPage = lazy(() => import('../features/map/MapPage'));

export const router = createBrowserRouter([
  {
    path: '/',
    element: <div>hajaCheck — 스켈레톤 (각 feature 담당이 라우트 추가)</div>,
  },
  // { path: '/login', ... }                    — features/auth
  // { path: '/dashboard', ... }                — features/dashboard
  // { path: '/facilities', ... }               — features/facility
  // { path: '/inspections/:id/viewer', ... }   — features/inspection
  {
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
