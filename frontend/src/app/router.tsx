// 라우트 정의는 이 파일 한 곳에 집중 — React_코드_컨벤션.md §7
// 경로: kebab-case / 인증 가드: ProtectedRoute, AdminRoute / 페이지 lazy loading 기본
import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AdminRoute } from '../shared/components/AdminRoute';
import { PlatformAdminRoute } from '../shared/components/PlatformAdminRoute';
import { CounselorRoute } from '../shared/components/CounselorRoute';
import { ProtectedRoute } from '../shared/components/ProtectedRoute';
import { LoadingSpinner } from '../shared/components/LoadingSpinner';
import LandingPage from '../features/landing/LandingPage';
import { COMPANY_DASHBOARD_ROLES } from '../shared/constants/roles';
import { PLATFORM_ADMIN_ROUTE } from '../shared/constants/routes';
import { AppShellRoute } from './AppShellRoute';
import { PlatformAdminShellRoute } from './PlatformAdminShellRoute';
import { CounselorShellRoute } from './CounselorShellRoute';

const MapPage = lazy(() => import('../features/map/MapPage'));
const ResultViewerPage = lazy(() =>
  import('../features/inspection/pages/ResultViewerPage').then((m) => ({
    default: m.ResultViewerPage,
  })),
);

// 보고서 생성 — 이슈 #621/#679, HAJA-343/HAJA-373
const ReportGeneratePage = lazy(() =>
  import('../features/report/pages/ReportGeneratePage').then((m) => ({
    default: m.ReportGeneratePage,
  })),
);

// 보고서 생성 진입점 — GitHub #876, Jira HAJA-451
const ReportEntryPage = lazy(() =>
  import('../features/report/pages/ReportEntryPage').then((m) => ({
    default: m.ReportEntryPage,
  })),
);

// 점검(회차) 생성 — API 명세서 v0.3 AP-004
// 보고서 목록/이력 관리 — 이슈 #463
const ReportListPage = lazy(() =>
  import('../features/report/pages/ReportListPage').then((m) => ({
    default: m.ReportListPage,
  })),
);

const InspectionCreatePage = lazy(() =>
  import('../features/inspection/pages/InspectionCreatePage').then((m) => ({
    default: m.InspectionCreatePage,
  })),
);

// AI 분석 실행/상태 — API 명세서 v0.3 AP-006(dev-05-04). 실 진행률 API 배선 전, 시안 목데이터 화면.
const AiAnalysisStatusPage = lazy(() =>
  import('../features/inspection/pages/AiAnalysisStatusPage').then((m) => ({
    default: m.AiAnalysisStatusPage,
  })),
);

const LoginPage = lazy(() =>
  import('../features/auth/pages/LoginPage').then((m) => ({ default: m.LoginPage })),
);

// 플랫폼 관리자 콘솔 — 라우팅/로그인 게이트/nav 뼈대(#535, Figma node 973-2520)
const PlatformAdminLoginPage = lazy(() =>
  import('../features/platform-admin/pages/PlatformAdminLoginPage').then((m) => ({
    default: m.PlatformAdminLoginPage,
  })),
);

// 상담원 전용 로그인 — PlatformAdminLoginPage와 동일 디자인, 라벨만 "상담원 로그인"
const CounselorLoginPage = lazy(() =>
  import('../features/counsel/pages/CounselorLoginPage').then((m) => ({
    default: m.CounselorLoginPage,
  })),
);
// 플랫폼 관리자 > 사용자 관리(#577) — 기업 관리자 콘솔의 AdminUsersPage(#405)를 그대로 옮긴 실 화면
const PlatformAdminUsersPage = lazy(() =>
  import('../features/platform-admin/pages/PlatformAdminUsersPage').then((m) => ({
    default: m.PlatformAdminUsersPage,
  })),
);
// 플랫폼 관리자 > 플랜·쿼터 관리(#625) — 기업 관리자 콘솔의 PlanQuotaPage(#508)를 그대로 옮긴 실 화면
const PlatformAdminPlanQuotaPage = lazy(() =>
  import('../features/platform-admin/pages/PlatformAdminPlanQuotaPage').then((m) => ({
    default: m.PlatformAdminPlanQuotaPage,
  })),
);
// 플랫폼 관리자 > 서비스 통계(#634) — placeholder를 실 화면으로 교체
const PlatformAdminStatsPage = lazy(() =>
  import('../features/platform-admin/pages/PlatformAdminStatsPage').then((m) => ({
    default: m.PlatformAdminStatsPage,
  })),
);
// 플랫폼 관리자 > 시스템 모니터링(#729) — placeholder를 실 화면으로 교체
const PlatformAdminMonitoringPage = lazy(() =>
  import('../features/platform-admin/pages/PlatformAdminMonitoringPage').then((m) => ({
    default: m.PlatformAdminMonitoringPage,
  })),
);
// 플랫폼 관리자 > 상담 관리(#1168) — placeholder를 실 화면으로 교체. 페이지 파일은
// features/counsel/pages에 있다(패널·훅·API가 전부 counsel feature 소속이라 cross-feature import
// 회피 — 위 PlatformAdminCounselsPage.tsx 상단 주석 참고).
const PlatformAdminCounselsPage = lazy(() =>
  import('../features/counsel/pages/PlatformAdminCounselsPage').then((m) => ({
    default: m.PlatformAdminCounselsPage,
  })),
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
// 초대 코드 입력(#799) — 소셜 최초 로그인 시 company_id 없는 사용자가 회사에 연결하는 화면
const InviteCodePage = lazy(() =>
  import('../features/auth/pages/InviteCodePage').then((m) => ({
    default: m.InviteCodePage,
  })),
);

const DashboardPage = lazy(() =>
  import('../features/dashboard/pages/DashboardPage').then((m) => ({
    default: m.DashboardPage,
  })),
);

// 다음 점검일 도래(dev-03-02, #543) — AI 주간 브리핑과 달리 실제 화면 자체가 없었던 독립 페이지
const UpcomingInspectionsPage = lazy(() =>
  import('../features/dashboard/pages/UpcomingInspectionsPage').then((m) => ({
    default: m.UpcomingInspectionsPage,
  })),
);

// "최근 점검" 카드 "전체보기"(신규) — 위젯(RecentInspectionsTable, 상위 10건 고정)의 전체 목록 화면
const RecentInspectionsFullListPage = lazy(() =>
  import('../features/dashboard/pages/RecentInspectionsFullListPage').then((m) => ({
    default: m.RecentInspectionsFullListPage,
  })),
);

const DefectListPage = lazy(() =>
  import('../features/defect/pages/DefectListPage').then((m) => ({
    default: m.DefectListPage,
  })),
);

// 점검 상세(카드형) — HAJA-393/394(#725/#726), 하자 목록 "목록 보기" 탭(점검 단위) 로우 클릭 시 이동.
// 하자 단건 상세 대신 점검 단위 하자 목록 경로를 사용한다.
const InspectionDefectsPage = lazy(() =>
  import('../features/defect/pages/InspectionDefectsPage').then((m) => ({
    default: m.InspectionDefectsPage,
  })),
);

// 마이페이지 — 내 플랜 (HAJA-185, #212)
const MyPlanPage = lazy(() =>
  import('../features/mypage/pages/MyPlanPage').then((m) => ({ default: m.MyPlanPage })),
);

// 마이페이지 — 내 정보 (HAJA-361, #659)
const MyProfilePage = lazy(() =>
  import('../features/mypage/pages/MyProfilePage').then((m) => ({ default: m.MyProfilePage })),
);

// 마이페이지 — 내 점검 이력 / 보고서 (HAJA-366, #668)
const MyInspectionsPage = lazy(() =>
  import('../features/mypage/pages/MyInspectionsPage').then((m) => ({
    default: m.MyInspectionsPage,
  })),
);

// 토스페이먼츠 결제창 연동 — 성공/실패 리다이렉트 진입점 (#989, HAJA-490)
const PaymentSuccessPage = lazy(() =>
  import('../features/mypage/pages/PaymentSuccessPage').then((m) => ({
    default: m.PaymentSuccessPage,
  })),
);
const PaymentFailPage = lazy(() =>
  import('../features/mypage/pages/PaymentFailPage').then((m) => ({ default: m.PaymentFailPage })),
);

// 관리자 > 사용자 관리 (Figma node 177-2017)
const AdminUsersPage = lazy(() =>
  import('../features/admin/pages/AdminUsersPage').then((m) => ({
    default: m.AdminUsersPage,
  })),
);

// 관리자 > 플랜·쿼터 관리 (Figma node 1197-3519)
const PlanQuotaPage = lazy(() =>
  import('../features/admin/pages/PlanQuotaPage').then((m) => ({
    default: m.PlanQuotaPage,
  })),
);

// 관리자 > AI 분석 현황 모니터링(신규) — 같은 회사 소속 검사자들의 분석 작업 진행/완료를 관리자가 확인
const AdminAnalysisJobsPage = lazy(() =>
  import('../features/admin/pages/AdminAnalysisJobsPage').then((m) => ({
    default: m.AdminAnalysisJobsPage,
  })),
);

// 플랫폼 관리자 > RAG 문서 관리 (#22/HAJA-35, PRD FR-8-B)
const RagDocumentsPage = lazy(() =>
  import('../features/admin/pages/RagDocumentsPage').then((m) => ({
    default: m.RagDocumentsPage,
  })),
);

const FacilityListPage = lazy(() =>
  import('../features/facility/pages/FacilityListPage').then((m) => ({
    default: m.FacilityListPage,
  })),
);

// 시설물 상세 — Figma "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401)
const FacilityDetailPage = lazy(() =>
  import('../features/facility/pages/FacilityDetailPage').then((m) => ({
    default: m.FacilityDetailPage,
  })),
);

// 점검 주기 설정 — dev-04-03, FR-019
const InspectionCycleSettingsPage = lazy(() =>
  import('../features/facility/pages/InspectionCycleSettingsPage').then((m) => ({
    default: m.InspectionCycleSettingsPage,
  })),
);

// 시설물 상세 하위 드릴다운 — 하자 정보 패널 + 회차 간 비교 (dev-04-02, #489).
// /facilities/:id(시설물 개요, dev-05-02·#504)에서 특정 하자로 드릴다운하는 화면이라
// /facilities/:id/defects/:defectId 하위 경로를 쓴다(#504와의 라우트 충돌 회피).
const FacilityDefectDetailPage = lazy(() =>
  import('../features/facility/pages/FacilityDefectDetailPage').then((m) => ({
    default: m.FacilityDefectDetailPage,
  })),
);
const FacilityInspectionComparePage = lazy(() =>
  import('../features/facility/pages/FacilityInspectionComparePage').then((m) => ({
    default: m.FacilityInspectionComparePage,
  })),
);

const StatisticsPage = lazy(() =>
  import('../features/statistics/pages/StatisticsPage').then((m) => ({
    default: m.StatisticsPage,
  })),
);

// 고객지원 > AI 어시스턴트 (dev-08-01, HAJA-32, FR-6 RAG 법규 Q&A)
const AiAssistantPage = lazy(() =>
  import('../features/support/pages/AiAssistantPage').then((m) => ({
    default: m.AiAssistantPage,
  })),
);

// 고객지원 > 내 상담 이력 (#20, HAJA-33)
const CounselHistoryPage = lazy(() =>
  import('../features/counsel/pages/CounselHistoryPage').then((m) => ({
    default: m.CounselHistoryPage,
  })),
);

// 고객지원 > 상담 챗봇 (#20, HAJA-33)
const ChatBotPage = lazy(() =>
  import('../features/counsel/pages/ChatBotPage').then((m) => ({
    default: m.ChatBotPage,
  })),
);

// 상담원 콘솔 (#1001, HAJA-495) — 대기열+실시간 채팅 마스터-디테일 단일 페이지(피그마 디자인 반영,
// CounselorQueuePage/CounselorChatPage 별도 페이지 구조를 통합).
const CounselorConsolePage = lazy(() =>
  import('../features/counsel/pages/CounselorConsolePage').then((m) => ({
    default: m.CounselorConsolePage,
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
    element: <LandingPage />,
  },
  {
    path: '/login',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <LoginPage />
      </Suspense>
    ),
  }, // — features/auth (HAJA-160, #157)
  {
    path: '/platform-admin/login',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <PlatformAdminLoginPage />
      </Suspense>
    ),
  }, // — features/platform-admin 플랫폼 관리자 전용 로그인, 기업회원 /login과 분리(#535)
  {
    path: '/counsel-console/login',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <CounselorLoginPage />
      </Suspense>
    ),
  }, // — features/counsel 상담원 전용 로그인, 기업회원 /login과 분리
  {
    path: '/policy/terms-of-service',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <TermsOfServicePage />
      </Suspense>
    ),
  },
  {
    path: '/policy/privacy',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <PrivacyPolicyPage />
      </Suspense>
    ),
  },
  {
    path: '/signup/company',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <CompanySignupPage />
      </Suspense>
    ),
  },
  {
    path: '/signup/company/pending',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <CompanySignupPendingPage />
      </Suspense>
    ),
  },
  {
    path: '/find-id',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <FindIdPage />
      </Suspense>
    ),
  }, // — features/auth 기업 인증 플로우 (HAJA-170, #187)
  {
    path: '/find-password',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <FindPasswordPage />
      </Suspense>
    ),
  }, // — features/auth 비밀번호 찾기 1단계 (#301, HAJA-224)
  {
    path: '/reset-password',
    element: (
      <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
        <ResetPasswordPage />
      </Suspense>
    ),
  }, // — features/auth 비밀번호 찾기 2단계, 메일 링크 진입 (#301, HAJA-224)
  {
    path: '/invite-code',
    // 로그인은 됐지만 company_id가 없는 상태(소셜 최초 로그인)라 AppShell(사이드바) 밖,
    // ProtectedRoute만으로 감싼 독립 화면 — CompanySignupPendingPage와 동일한 스탠드얼론 패턴.
    element: (
      <ProtectedRoute>
        <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
          <InviteCodePage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/auth 초대 코드 입력 (#799)
  {
    path: '/payments/success',
    // 토스페이먼츠 결제창(외부 페이지)에서 successUrl로 돌아오는 착지점 — AppShell(사이드바) 밖,
    // ProtectedRoute만으로 감싼 독립 화면(InviteCodePage와 동일한 스탠드얼론 패턴, handoff §3
    // "두 라우트 모두 인증 필요 영역에 배치"). 비로그인 진입 시 기존 가드 흐름 그대로 /login으로.
    element: (
      <ProtectedRoute>
        <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
          <PaymentSuccessPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/mypage 토스페이먼츠 결제 성공 콜백 (#989, HAJA-490)
  {
    path: '/payments/fail',
    element: (
      <ProtectedRoute>
        <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
          <PaymentFailPage />
        </Suspense>
      </ProtectedRoute>
    ),
  }, // — features/mypage 토스페이먼츠 결제 실패 콜백 (#989, HAJA-490)
  {
    // 로그인 후 내부 페이지 공통 앱 셸(SideNavBar+Header, AppLayout) — nested route로 강제 연결(HAJA-186, #217 후속).
    // ProtectedRoute로 부모 전체를 감싸 자식 라우트를 일괄 보호한다(#231, HAJA-189) —
    // 이 셸에 새 페이지를 포함하려면: children에 라우트 추가 + handle에 breadcrumb/activeHref 선언만 하면 됨
    // (페이지 컴포넌트는 AppLayout을 직접 감쌀 필요 없음 — AppShellRoute.tsx 참조)
    //
    // allowedRoles(#1513): 이 셸은 기업회원 대시보드 전용이다. 서버가 로그인 단계에서 포털을 갈라
    // 놓았지만(POST /api/auth/login은 ADMIN/INSPECTOR/USER만 허용), 이미 세션을 가진 PLATFORM_ADMIN·
    // COUNSELOR가 주소창으로 /dashboard에 직접 들어오는 경로는 라우터가 막아야 한다. 거부 대상은
    // ProtectedRoute가 resolveRoleHomeRoute로 각자 콘솔에 돌려보낸다(대시보드 고정이면 무한 루프).
    element: (
      <ProtectedRoute allowedRoles={COMPANY_DASHBOARD_ROLES}>
        <AppShellRoute />
      </ProtectedRoute>
    ),
    children: [
      {
        path: '/dashboard',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <DashboardPage />
          </Suspense>
        ),
        handle: { breadcrumb: [{ label: '홈' }, { label: '대시보드' }], activeHref: '/dashboard' },
      }, // — features/dashboard (HAJA-17)
      {
        path: '/dashboard/upcoming-inspections',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <UpcomingInspectionsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '대시보드' }, { label: '다음 점검일 도래' }],
          activeHref: '/dashboard/upcoming-inspections',
        },
      }, // — features/dashboard 다음 점검일 도래 (dev-03-02, #543)
      {
        path: '/dashboard/recent-inspections',
        element: (
          <Suspense fallback={<div>불러오는 중...</div>}>
            <RecentInspectionsFullListPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '대시보드' }, { label: '최근 점검 전체보기' }],
          activeHref: '/dashboard/recent-inspections',
        },
      }, // — features/dashboard "최근 점검" 카드 전체보기(신규)
      {
        // 정적 경로라 SideNavBar href('/defects/list')와 동일하게 맞춰 activeHref 매핑 없이도
        // 사이드바 클릭이 그대로 동작한다(하위 :id 상세는 동적 세그먼트라 SideNavBar 플레이스홀더
        // href와 다를 수밖에 없어 handle.activeHref로 매핑하는 것과 대조 — HAJA-30).
        path: '/defects/list',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <DefectListPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '하자 관리' }, { label: '하자 목록' }],
          activeHref: '/defects/list',
        },
      }, // — features/defect (HAJA-30)
      {
        path: '/dashboard/ai-weekly-briefing',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <DashboardPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '대시보드' }, { label: 'AI 주간 브리핑 카드' }],
          activeHref: '/dashboard/ai-weekly-briefing',
        },
      }, // — 사이드바 "AI 주간 브리핑 카드"가 가리키던 미구현 라우트(#478). AiBriefingCard는 별도 화면이
      // 아니라 /dashboard 인라인 위젯이라 같은 DashboardPage를 렌더링하고 위젯 위치로 스크롤한다
      // (DashboardPage.tsx 참조) — #472와 동일한 라우트-메뉴 불일치 유형.
      {
        path: '/inspections/:id/defects',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <InspectionDefectsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '하자 관리' }, { label: '하자 상세' }],
          // '하자 관리'는 하위메뉴 없는 단일 링크(href='/defects/list')로 정리돼(#499) 있어
          // 그 값으로 맞춘다.
          activeHref: '/defects/list',
        },
      }, // — features/defect 점검 상세(카드형, HAJA-393/394, #725/#726)
      {
        path: '/mypage/plan',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <MyPlanPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '마이페이지' }, { label: '내 플랜' }],
          activeHref: '/mypage/plan',
        },
      }, // — features/mypage (HAJA-185, #212)
      {
        path: '/mypage/profile',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <MyProfilePage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '마이페이지' }, { label: '내 정보' }],
          activeHref: '/mypage/profile',
        },
      }, // — features/mypage 내 정보 (HAJA-361, #659)
      {
        path: '/mypage/inspections',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <MyInspectionsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '마이페이지' }, { label: '내 점검 이력 / 보고서' }],
          activeHref: '/mypage/inspections',
        },
      }, // — features/mypage 내 점검 이력 / 보고서 (HAJA-366, #668)
      {
        // #1361 — 하위 경로 없는 '/facilities' 자체는 매칭되는 라우트가 없어 주소창에 직접
        // 입력하면 React Router 기본 404 에러 페이지가 떴다. 사이드바는 항상 하위 경로로만
        // 이동해 평소엔 안 드러난다. /platform-admin(861-862줄)과 동일한 <Navigate> 패턴.
        path: '/facilities',
        element: <Navigate to="/facilities/list" replace />,
      },
      {
        path: '/facilities/map',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <MapPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '시설물 관리' }, { label: '지도 뷰' }],
          activeHref: '/facilities/map',
        },
      }, // — features/map (#28, HAJA-150 §129 재오픈: 공용 셸 편입 + SideNavBar 경로 버그 수정)
      {
        path: '/inspections/create',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <InspectionCreatePage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '점검 관리' }, { label: '점검(회차) 생성' }],
          activeHref: '/inspections/create',
        },
      }, // — features/inspection 점검(회차) 생성 (API 명세서 v0.3 AP-004)
      {
        path: '/inspections/ai-analysis',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <AiAnalysisStatusPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '점검 관리' }, { label: 'AI 분석 실행/상태' }],
          activeHref: '/inspections/ai-analysis',
        },
      }, // — features/inspection AI 분석 실행/상태 (API 명세서 v0.3 AP-006, dev-05-04) — 촬영 데이터 업로드는
      // 회의 후 반영된 시안대로 이 화면에 통합됨(별도 /inspections/media-upload 라우트는 폐지).
      {
        // 실제 점검 회차의 분석 상태(백엔드 폴링) — 위 정적 경로는 사이드바 직접 진입용 데모(id 없음,
        // 빈 상태만 표시)이고, 이 경로는 점검 생성 후 진짜 분석 진행 상황을 보여준다(AiAnalysisStatusPage가
        // :id 유무로 두 모드를 분기).
        path: '/inspections/:id/analysis',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <AiAnalysisStatusPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '점검 관리' }, { label: 'AI 분석 실행/상태' }],
          activeHref: '/inspections/ai-analysis',
        },
      },
      {
        path: '/inspections/:id/viewer',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <ResultViewerPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '점검 관리' }, { label: '분석 결과 뷰어' }],
          activeHref: '/inspections/1/viewer',
        },
      }, // — features/inspection FR-4 (HAJA-249, #249)
      {
        path: '/inspections/:id/reports',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <ReportEntryPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '점검 관리' }, { label: '보고서 생성' }],
          activeHref: '/inspections/1/reports',
        },
      }, // — features/report 보고서 생성 진입점 (#876, HAJA-451)
      {
        path: '/reports',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <ReportListPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '보고서' }, { label: '보고서 목록 / 이력 관리' }],
          activeHref: '/reports',
        },
      }, // — features/report 보고서 목록/이력 관리 (#463)
      {
        path: '/reports/:reportId',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <ReportGeneratePage />
          </Suspense>
        ),
          handle: {
            breadcrumb: [{ label: '홈' }, { label: '보고서' }, { label: '보고서 편집·미리보기' }],
            activeHref: '/reports/1',
            exportActiveHref: '/reports/1?mode=export',
          },
        }, // — features/report 보고서 상세·PDF 내보내기 (#1087)
      {
        path: '/facilities/:id',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <FacilityDetailPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '시설물 관리' }, { label: '강남 오피스타워 A동' }],
          // SideNavBar '시설물 관리' 그룹엔 '/facilities/detail' href를 가진 항목이 없다(항상 없었음) —
          // '시설물 목록/등록'(href='/facilities/list')과 맞춰 그룹이 자동으로 펼쳐지고 하이라이트되게
          // 한다(코드 리뷰 P1 지적, #499와 함께 정리).
          activeHref: '/facilities/list',
        },
      }, // — features/facility 시설물 상세 (Figma node-id 1-1401)
      {
        path: '/facilities/:id/defects/:defectId',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <FacilityDefectDetailPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '시설물 목록' }, { label: '시설물 상세' }, { label: '하자 상세' }],
          // SideNavBar '시설물 관리' 그룹엔 '/facilities/detail' href를 가진 항목이 없다(항상 없었음) —
          // '시설물 목록/등록'(href='/facilities/list')과 맞춰 그룹이 자동으로 펼쳐지고 하이라이트되게
          // 한다(코드 리뷰 P1 지적, #499와 함께 정리).
          activeHref: '/facilities/list',
        },
      }, // — features/facility 하자 정보 패널 (dev-04-02, #489). 위 시설물 개요(/facilities/:id,
      // #504)에서 특정 하자를 드릴다운하는 하위 화면 — 같은 :id로는 #504와 라우트가 겹쳐
      // :defectId를 추가한 하위 경로를 쓴다.
      {
        path: '/facilities/:id/defects/:defectId/compare',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <FacilityInspectionComparePage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [
            { label: '시설물 목록' },
            { label: '시설물 상세' },
            { label: '회차 비교' },
          ],
          // SideNavBar '시설물 관리' 그룹엔 '/facilities/detail' href를 가진 항목이 없다(항상 없었음) —
          // '시설물 목록/등록'(href='/facilities/list')과 맞춰 그룹이 자동으로 펼쳐지고 하이라이트되게
          // 한다(코드 리뷰 P1 지적, #499와 함께 정리).
          activeHref: '/facilities/list',
        },
      }, // — features/facility 회차 간 비교 (dev-04-02, #489). 부모(하자 상세)와 동일 사이드바 하이라이트 유지.
      {
        path: '/facilities/inspection-cycle',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <InspectionCycleSettingsPage />
          </Suspense>
        ),
        handle: {
          // #1135 — 특정 시설물명(강남 오피스타워 A동)을 하드코딩하지 않는다. #1129 이전
          // DEFAULT_FACILITY_ID=3 폴백 시절 "항상 그 시설물이 선택됨"을 전제로 붙인 값이었는데,
          // #1129 이후엔 시설물이 선택되지 않은 채로도 진입 가능해 실제와 다른 이름이 떴다.
          // Figma는 일반 텍스트("점검 주기 설정")만 쓴다.
          breadcrumb: [{ label: '시설물 관리' }, { label: '점검 주기 설정' }],
          activeHref: '/facilities/inspection-cycle',
        },
      }, // — features/facility 점검 주기 설정 (dev-04-03, FR-019)
      {
        path: '/facilities/list',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <FacilityListPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '시설물 관리' }, { label: '시설물 목록/등록' }],
          activeHref: '/facilities/list',
        },
      }, // — features/facility 시설물 목록/등록 (dev-04-01, FR-003). SideNavBar href('/facilities/list')와
      // 불일치하던 구 경로('/facilities', 셸 밖)를 셸 안으로 이동해 정정(#472).
      {
        path: '/admin/users',
        // 관리자 전용 — 부모 AppShell의 ProtectedRoute는 인증만 보므로 AdminRoute를 덧댄다(#378, 컨벤션 §7)
        element: (
          <AdminRoute>
            <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
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
        path: '/admin/plans-quota',
        // 관리자 전용 — 부모 AppShell의 ProtectedRoute는 인증만 보므로 AdminRoute를 덧댄다(#508, 컨벤션 §7)
        element: (
          <AdminRoute>
            <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
              <PlanQuotaPage />
            </Suspense>
          </AdminRoute>
        ),
        handle: {
          breadcrumb: [{ label: '관리자' }, { label: '플랜·쿼터 관리' }],
          activeHref: '/admin/plans-quota',
        },
      }, // — features/admin 플랜·쿼터 관리 (Figma node 1197-3519)
      {
        path: '/admin/analysis-jobs',
        // 관리자 전용 — 부모 AppShell의 ProtectedRoute는 인증만 보므로 AdminRoute를 덧댄다(컨벤션 §7)
        element: (
          <AdminRoute>
            <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
              <AdminAnalysisJobsPage />
            </Suspense>
          </AdminRoute>
        ),
        handle: {
          breadcrumb: [{ label: '관리자' }, { label: 'AI 분석 현황' }],
          activeHref: '/admin/analysis-jobs',
        },
      }, // — features/admin AI 분석 현황 모니터링(신규)
      {
        path: '/statistics',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <StatisticsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '홈' }, { label: '통계' }],
          activeHref: '/statistics',
        },
      }, // — features/statistics (HAJA-40, #27)
      {
        path: '/support/ai-assistant',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <AiAssistantPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '고객지원' }, { label: 'AI 어시스턴트' }],
          activeHref: '/support/ai-assistant',
        },
      }, // — features/support (dev-08-01, HAJA-32, FR-6)
      {
        path: '/support/history',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <CounselHistoryPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '고객지원' }, { label: '내 상담 이력' }],
          activeHref: '/support/history',
        },
      }, // — features/counsel (#20, HAJA-33)
      {
        path: '/support/chat-bot',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <ChatBotPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '고객지원' }, { label: '상담 챗봇' }],
          activeHref: '/support/chat-bot',
        },
      }, // — features/counsel (#20, HAJA-33)
    ],
  },
  {
    // 플랫폼 관리자 콘솔 전용 셸 — 일반 사용자 AppShellRoute와 별개(#535). PlatformAdminRoute가
    // 미인증→/platform-admin/login, role≠PLATFORM_ADMIN→/dashboard로 부모 단계에서 차단한다.
    element: (
      <PlatformAdminRoute>
        <PlatformAdminShellRoute />
      </PlatformAdminRoute>
    ),
    children: [
      {
        path: PLATFORM_ADMIN_ROUTE,
        element: <Navigate to="/platform-admin/stats" replace />,
      },
      {
        path: '/platform-admin/users',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <PlatformAdminUsersPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '플랫폼 관리자' }, { label: '사용자 관리' }],
          activeHref: '/platform-admin/users',
        },
      }, // — features/platform-admin 사용자 관리 실 화면 (#577, features/admin AdminUsersPage 이식)
      {
        path: '/platform-admin/plans-quota',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <PlatformAdminPlanQuotaPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '플랫폼 관리자' }, { label: '플랜·쿼터 관리' }],
          activeHref: '/platform-admin/plans-quota',
        },
      }, // — features/platform-admin 플랜·쿼터 관리 실 화면 (#625, features/admin PlanQuotaPage 이식)
      {
        path: '/platform-admin/counsels',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <PlatformAdminCounselsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '플랫폼 관리자' }, { label: '상담 관리' }],
          activeHref: '/platform-admin/counsels',
        },
      },
      {
        path: '/platform-admin/rag-documents',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <RagDocumentsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '플랫폼 관리자' }, { label: 'RAG 문서 관리' }],
          activeHref: '/platform-admin/rag-documents',
        },
      }, // — features/admin RAG 문서 관리 (#22/HAJA-35, PRD FR-8-B)
      {
        path: '/platform-admin/stats',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <PlatformAdminStatsPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '플랫폼 관리자' }, { label: '대시보드' }],
          activeHref: '/platform-admin/stats',
        },
      },
      {
        path: '/platform-admin/monitoring',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <PlatformAdminMonitoringPage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '플랫폼 관리자' }, { label: '시스템 모니터링' }],
          activeHref: '/platform-admin/monitoring',
        },
      }, // — features/platform-admin 시스템 모니터링 실 화면 (#729)
    ],
  }, // — features/platform-admin (#535). 각 메뉴 실 기능은 후속 이슈.
  {
    // 상담원 콘솔 전용 셸 — 일반 사용자 AppShellRoute·PlatformAdminShellRoute와 별개(#1001, HAJA-495).
    // CounselorRoute가 미인증→/counsel-console/login, role≠COUNSELOR→/dashboard로 부모 단계에서 차단한다.
    element: (
      <CounselorRoute>
        <CounselorShellRoute />
      </CounselorRoute>
    ),
    children: [
      {
        path: '/counsel-console/queue',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <CounselorConsolePage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '상담원 콘솔' }, { label: '상담 대기열' }],
          activeHref: '/counsel-console/queue',
        },
      }, // — features/counsel 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 티켓 미선택 상태
      {
        path: '/counsel-console/tickets/:id',
        element: (
          <Suspense fallback={<LoadingSpinner className="flex items-center justify-center gap-2 py-6 min-h-[50vh]" />}>
            <CounselorConsolePage />
          </Suspense>
        ),
        handle: {
          breadcrumb: [{ label: '상담원 콘솔' }, { label: '상담 대기열' }, { label: '실시간 채팅' }],
          // 사이드바엔 '상담 대기열' 메뉴 하나뿐이라(COUNSELOR_NAV_ITEM) 채팅 화면 진입 시에도
          // 그 메뉴가 강조되도록 맞춘다 — 다른 동적 상세 라우트와 동일한 관례.
          activeHref: '/counsel-console/queue',
        },
      }, // — features/counsel 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 티켓 선택 상태(같은 컴포넌트, :id로 분기)
    ],
  }, // — features/counsel 상담원 콘솔(#1001, HAJA-495)
  // 구 '/facilities'(셸 밖) 라우트는 '/facilities/list'(셸 안, 위 AppShellRoute children)로 이동됨(#472).
  // '/defects/list' 는 AppShellRoute 자식(위 children 배열)으로 등록됨 — features/defect (HAJA-30)
  // { path: '/support', ... }                  — features/support
  // 관리자: /admin/users 구현 완료(위 AppShell children) — 나머지 관리자 화면은 #21 하위 이슈로 분리
]);
