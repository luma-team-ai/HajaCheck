import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import { mockMyReports } from '../mocks/myInspections.mock';
import type { MyReportCard } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// 내 보고서 목록 (HAJA-366, #668). useMyPlan과 동일한 폴백 규약 — 실 다운로드/미리보기 연동은
// 후속(보고서 PDF GET /api/reports/{id}/pdf/{storageKey}는 실존하나 이번 스코프는 목록 UI까지).
export function useMyReports() {
  return useQuery<MyReportCard[], ApiError>({
    queryKey: ['mypage', 'reports'],
    queryFn: () => fetchWithFallback(() => mypageApi.getReports().then((res) => res.data), mockMyReports),
  });
}
