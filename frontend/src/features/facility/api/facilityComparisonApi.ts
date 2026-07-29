import { api } from '../../../shared/api/axios';
import type { InspectionComparisonResult } from '../types';

// 회차 간 비교(dev-04-02, #489) — 실 백엔드 GET /api/facilities/{id}/compare(HAJA-531/#1112,
// PR #1127) 연동. before/after 생략 시 서버가 그 시설물의 실제 최근 2개 회차로 자동 대체한다(#1157) —
// 화면 최초 진입 시 프론트가 그 시설물의 유효한 회차를 미리 알 방법이 없기 때문(회차 목록 자체가
// 이 응답의 availableCycles로만 내려옴).
export const facilityComparisonApi = {
  getComparison: (facilityId: string, beforeCycle?: number, afterCycle?: number) =>
    api.get<InspectionComparisonResult>(`/facilities/${facilityId}/compare`, {
      params: { before: beforeCycle, after: afterCycle },
    }),
};