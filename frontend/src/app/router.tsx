// 라우트 정의는 이 파일 한 곳에 집중 — React_코드_컨벤션.md §7
// 경로: kebab-case / 인증 가드: ProtectedRoute, AdminRoute / 페이지 lazy loading 기본
import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import { AdminRoute } from '../shared/components/AdminRoute';
import { ProtectedRoute } from '../shared/components/ProtectedRoute';
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

// 이용약관 / 개인정보처리방침 — 랜딩 푸터 "법적 고지" 연결
const TermsOfServicePage = lazy(() =>
  import('../features/policy/pages/TermsOfServicePage').then((m) => ({
    default: m.TermsOfServicePage,
  })),
);
const PrivacyPolicyPage = lazy(() =>
  import('../features/policy/pages/PrivacyPolicyPage').then((m) => ({
    default: m.PrivacyPolicyPage,
  })),
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
// 비밀번호 찾기 — 이메일 링크 방식(#301, HAJA-224)
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

const DefectDetailPage = lazy(() =>
  import('../features/defect/pages/DefectDetailPage').then((m) => ({
    default: m.DefectDetailPage,
  })),
);

// 마이페이지 — 내 플랜 (HAJA-185, #212)
const MyPlanPage = lazy(() =>
  import('../features/mypage/pages/MyPlanPage').then((m) => ({ default: m.MyPlanPage })),
);

// 관리자 > 사용자 관리 (Figma node 177-2017)
const AdminUsersPage = lazy(() =>
  import('../features/admin/pages/AdminUsersPage').then((m) => ({
    default: m.AdminUsersPage,
  })),
);

const FacilityListPage = lazy(() =>
  import('../features/facility/pages/FacilityListPage').then((m) => ({
    default: m.FacilityListPage,
  })),
);

// 점검 주기 설정 — dev-04-03, FR-019
const InspectionCycleSettingsPage = lazy(() =>
  import('../features/facility/pages/InspectionCycleSettingsPage').then((m) => ({
    default: m.InspectionCycleSettingsPage,
  })),
);

// 고객지원 > AI 어시스턴트 (dev-08-01, HAJA-32, FR-6 RAG 법규 Q&A)
const AiAssistantPage = lazy(() =>
  import('../features/support/pages/AiAssistantPage').then((m) => ({
    default: m.AiAssistantPage,
  })),
);

const ChartShowcasePage = import.meta.env.DEV
  ? lazy(() =>
      import('../dev/charts/ChartShowcasePage').then((m) => ({
        default: m.ChartShowcasePage,
      })),
    )
  : null;

const DEV_ONLY_ROUTES = ChartShowcasePage
  ? [
      {
        path: '/dev/charts',
        element: (
          <Suspense fallback={<div>차트 쇼케이스를 불러오는 중...</div>}>
            <ChartShowcasePage />
          </Suspense>
        ),
      },
    ]
  : [];

export const router = createBrowserRouter([
  ...DEV_ONLY_ROUTES,
  {
    path: '/',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <LandingPage />
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
    path: '/policy/terms-of-service',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <TermsOfServicePage />
      </Suspense>
    ),
  },
  {
    path: '/policy/privacy',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <PrivacyPolicyPage />
      </Suspense>
    ),
  },
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
    path: '/find-password',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <FindPasswordPage />
      </Suspense>
    ),
  }, // — features/auth 비밀번호 찾기 1단계 (#301, HAJA-224)
  {
    path: '/reset-password',
    element: (
      <Suspense fallback={<div>불러오는 중...</div>}>
        <ResetPasswordPage />
      </Suspense>
    ),
  }, // — features/auth 비밀번호 찾기 2단계, 메일 링크 진입 (#301, HAJA-224)
  {
    // 로그인 후 내부 페이지 공통 앱 셸(SideNavBar+Header, AppLayout) — nested route로 강제 연결(HAJA-186, #217 후속).
    // ProtectedRoute로 부모 전체를 감싸 자식 라우트를 일괄 보호한다(#231, HAJA-189) —
    // 이 셸에 새 페이지를 포함하려면: children에 라우트 추가 + handle에 breadcrumb/activeHref 선언만 하면 됨
    // (페이지 컴포넌트는 AppLayout을 직접 감쌀 필요 없음 — AppShellRoute.tsx 참조)
    element: (
      <ProtectedRoute>
        <AppShellRoute />
      </ProtectedRoute>
    ),
    children: [
      {
        path: '/dashboard',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <DashboardPage />
          </Suspense>
        ),
        handle: { breadcrumb: [{ label: '홈' }, { label: '대시보드' }], activeHref: '/dashboard' },
      }, // — features/dashboard (HAJA-17)
      {
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
      {
        path: '/facilities/map',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <MapPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '시설물 관리' }, { label: '지도 뷰' }],
          activeHref: '/facilities/map',
        },
      }, // — features/map (#28, HAJA-150 §129 재오픈: 공용 셸 편입 + SideNavBar 경로 버그 수정)
      {
        path: '/inspections/:id/viewer',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <ResultViewerPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '점검 관리' }, { label: '분석 결과 뷰어' }],
          activeHref: '/inspections/1/viewer',
        },
      }, // — features/inspection FR-4 (HAJA-249, #249)
      {
        path: '/facilities/inspection-cycle',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <InspectionCycleSettingsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [
            { label: '시설물 관리' },
            { label: '강남 오피스타워 A동' },
            { label: '점검 주기' },
          ],
          activeHref: '/facilities/inspection-cycle',
        },
      }, // — features/facility 점검 주기 설정 (dev-04-03, FR-019)
      {
        path: '/admin/users',
        // 관리자 전용 — 부모 AppShell의 ProtectedRoute는 인증만 보므로 AdminRoute를 덧댄다(#378, 컨벤션 §7)
        element: (
          <AdminRoute>
            <Suspense fallback={<div>불러오는 중...</div>}>
              <AdminUsersPage />
            </Suspense>
          </AdminRoute>
        ),
        handle: {
          breadcrumb: [{ label: '관리자' }, { label: '사용자 관리' }],
          activeHref: '/admin/users',
        },
      }, // — features/admin (Figma node 177-2017)
      {
        path: '/support/ai-assistant',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <AiAssistantPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '고객지원' }, { label: 'AI 어시스턴트' }],
          activeHref: '/support/ai-assistant',
        },
      }, // — features/support (dev-08-01, HAJA-32, FR-6)
    ],
  },
  {
    // 셸(AppShellRoute) 중첩 밖 업무 라우트 — 인증 가드는 적용하되 AppLayout 셸 미포함(#231 관찰,
    // 셸 포함은 별도 후속 스코프).
    path: '/facilities',
    element: (
      <ProtectedRoute>
        <Suspense fallback={<div>불러오는 중...</div>}>
          <FacilityListPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/facility (dev-04-01, FR-003)
  // { path: '/defects', ... }                  — features/defect
  // { path: '/reports', ... }                  — features/report
  // { path: '/support', ... }                  — features/support
  // 관리자: /admin/users 구현 완료(위 AppShell children) — 나머지 관리자 화면은 #21 하위 이슈로 분리
]);
