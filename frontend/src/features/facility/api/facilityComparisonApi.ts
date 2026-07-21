import { api } from '../../../shared/api/axios';
import type { InspectionComparisonResult } from '../types';

// 회차 간 비교(dev-04-02, #489) — 백엔드 미구현, MSW 목 전용(facilityComparisonApi.handlers.ts).
export const facilityComparisonApi = {
  getComparison: (facilityId: string, beforeCycle: number, afterCycle: number) =>
    api.get<InspectionComparisonResult>(`/facilities/${facilityId}/compare`, {
      params: { before: beforeCycle, after: afterCycle },
    }),
};