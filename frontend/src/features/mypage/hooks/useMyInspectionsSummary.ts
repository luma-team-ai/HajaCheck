import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { MyInspectionsSummary } from '../types';

// 내 점검 이력/보고서 — KPI 4종 (HAJA-366/#668, BE 연동 #844/HAJA-442). period 필터가 없다
// (handoff §2-1 계약 — 요약은 항상 전체 기간 기준).
export function useMyInspectionsSummary() {
  return useQuery<MyInspectionsSummary, ApiError>({
    queryKey: ['mypage', 'inspections', 'summary'],
    queryFn: () => mypageApi.getInspectionsSummary().then((res) => res.data),
  });
}
