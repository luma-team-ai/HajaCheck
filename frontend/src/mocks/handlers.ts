// MSW 핸들러 집합 — feature가 늘어나면 각 feature의 `api/*.handlers.ts`를 여기 추가
import { adminAnalysisJobHandlers } from '../features/admin/api/adminAnalysisJobApi.handlers';
import { adminHandlers } from '../features/admin/api/adminApi.handlers';
import { adminPlanHandlers } from '../features/admin/api/adminPlanApi.handlers';
import { planQuotaHandlers } from '../features/admin/api/planQuotaApi.handlers';
import { ragDocumentHandlers } from '../features/admin/api/ragDocumentApi.handlers';
import { authHandlers } from '../features/auth/api/authApi.handlers';
import { dashboardHandlers } from '../features/dashboard/api/dashboardApi.handlers';
import { defectHandlers } from '../features/defect/api/defectApi.handlers';
import { facilityAssigneeHandlers } from '../features/facility/api/facilityAssigneeApi.handlers';
import { facilityComparisonHandlers } from '../features/facility/api/facilityComparisonApi.handlers';
import { facilityDefectHandlers } from '../features/facility/api/facilityDefectApi.handlers';
import { facilityHandlers } from '../features/facility/api/facilityApi.handlers';
import { inspectionHandlers } from '../features/inspection/api/inspectionApi.handlers';
import { mediaHandlers } from '../features/inspection/api/mediaApi.handlers';
import { menuHandlers } from '../features/menu/api/menuApi.handlers';
import { mypageHandlers } from '../features/mypage/api/mypageApi.handlers';
import { notificationHandlers } from '../features/notification/api/notificationApi.handlers';
import { monitoringHandlers } from '../features/platform-admin/api/monitoringApi.handlers';
import { planPolicyHandlers } from '../features/platform-admin/api/planPolicyApi.handlers';
import { planQuotaHandlers as platformAdminPlanQuotaHandlers } from '../features/platform-admin/api/planQuotaApi.handlers';
import { platformAdminCompanyHandlers } from '../features/platform-admin/api/platformAdminCompanyApi.handlers';
import { platformAdminUserHandlers } from '../features/platform-admin/api/platformAdminUserApi.handlers';
import { reportHandlers } from '../features/report/api/reportApi.handlers';
import { statisticsHandlers } from '../features/statistics/api/statisticsApi.handlers';
import { statsHandlers } from '../features/platform-admin/api/statsApi.handlers';
import { supportHandlers } from '../features/support/api/supportApi.handlers';
import { getEffectiveAuthHandlers, isHybridMode } from '../shared/utils/isHybridMode';

// hybrid는 실제 Spring 세션/인증을 사용해야 하므로 auth MSW가 실 로그인 요청을 가로채면 안 된다.
// true/미설정 dev에서는 기존 목 로그인 동작을 유지한다.
const hybridMode = isHybridMode(import.meta.env);
const effectiveAuthHandlers = getEffectiveAuthHandlers(import.meta.env, authHandlers);
const effectiveFacilityHandlers = hybridMode ? [] : facilityHandlers;
// hybrid에서는 실 백엔드가 계약을 가진 보고서 요청을 MSW가 가로채지 않게 한다.
// 백엔드 미구현 회사 목록/요약은 훅의 404 폴백으로 개발 화면을 유지한다.
const effectiveReportHandlers = hybridMode ? [] : reportHandlers;

export const allMockHandlers = [
  ...effectiveAuthHandlers,
  // facilityAssigneeHandlers(GET /api/facilities/assignable-users, 리터럴 경로)는 msw v2 등록 순서
  // 매칭이라 inspectionHandlers/facilityHandlers가 등록하는 GET /api/facilities/:id 캐치올보다
  // 반드시 앞에 와야 한다 — 안 그러면 :id='assignable-users'로 먼저 매치되어 항상 404가 난다(PR머신 P1).
  ...facilityAssigneeHandlers,
  ...inspectionHandlers,
  ...mediaHandlers,
  // defectHandlers(GET /api/inspections, GET /api/inspections/:id/defects — HAJA-393/394, #725/#726)는
  // 위 inspectionHandlers/mediaHandlers/facilityAssigneeHandlers가 이미 처리하는 /api/facilities,
  // /api/facilities/assignable-users, POST /api/inspections/:id/media에 대한 자체 목도 포함하지만
  // (feature 간 직접 import 금지로 각자 복제) msw는 먼저 등록된 핸들러가 우선하므로 여기서는 실제로
  // 새로 추가되는 두 엔드포인트만 유효하게 동작한다.
  ...defectHandlers,
  ...dashboardHandlers,
  ...statisticsHandlers,
  ...menuHandlers,
  ...mypageHandlers,
  ...effectiveFacilityHandlers,
  ...facilityDefectHandlers,
  ...facilityComparisonHandlers,
  ...adminHandlers,
  ...adminAnalysisJobHandlers,
  ...adminPlanHandlers,
  ...planQuotaHandlers,
  ...platformAdminUserHandlers,
  ...platformAdminPlanQuotaHandlers,
  ...planPolicyHandlers,
  ...platformAdminCompanyHandlers,
  ...statsHandlers,
  ...monitoringHandlers,
  ...effectiveReportHandlers,
  ...ragDocumentHandlers,
  ...supportHandlers,
  ...notificationHandlers,
];

// hybrid에서는 서비스워커가 데이터 요청을 가로채지 않아야 한다.
// 실서버 우선/실패 시 목 폴백은 각 query hook의 hybridFetchFallback이 담당한다.
// 이 경계를 두지 않으면 MSW가 200 목 응답을 먼저 반환해 실제 DB 데이터와 CUD 결과를
// 영구적으로 가리게 된다(#941, #943).
export const handlers = hybridMode ? [] : allMockHandlers;
