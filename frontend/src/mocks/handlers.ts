// MSW 핸들러 집합 — feature가 늘어나면 각 feature의 `api/*.handlers.ts`를 여기 추가
import { dashboardHandlers } from '../features/dashboard/api/dashboardApi.handlers';
import { inspectionHandlers } from '../features/inspection/api/inspectionApi.handlers';

export const handlers = [...inspectionHandlers, ...dashboardHandlers];
