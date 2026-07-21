// MSW 핸들러 집합 — feature가 늘어나면 각 feature의 `api/*.handlers.ts`를 여기 추가
import { adminHandlers } from '../features/admin/api/adminApi.handlers';
import { adminPlanHandlers } from '../features/admin/api/adminPlanApi.handlers';
import { planQuotaHandlers } from '../features/admin/api/planQuotaApi.handlers';
import { authHandlers } from '../features/auth/api/authApi.handlers';
import { dashboardHandlers } from '../features/dashboard/api/dashboardApi.handlers';
import { facilityComparisonHandlers } from '../features/facility/api/facilityComparisonApi.handlers';
import { facilityDefectHandlers } from '../features/facility/api/facilityDefectApi.handlers';
import { facilityHandlers } from '../features/facility/api/facilityApi.handlers';
import { inspectionHandlers } from '../features/inspection/api/inspectionApi.handlers';
import { mypageHandlers } from '../features/mypage/api/mypageApi.handlers';
import { notificationHandlers } from '../features/notification/api/notificationApi.handlers';
import { supportHandlers } from '../features/support/api/supportApi.handlers';

export const handlers = [
  ...authHandlers,
  ...inspectionHandlers,
  ...dashboardHandlers,
  ...mypageHandlers,
  ...facilityHandlers,
  ...facilityDefectHandlers,
  ...facilityComparisonHandlers,
  ...adminHandlers,
  ...adminPlanHandlers,
  ...planQuotaHandlers,
  ...supportHandlers,
  ...notificationHandlers,
];
