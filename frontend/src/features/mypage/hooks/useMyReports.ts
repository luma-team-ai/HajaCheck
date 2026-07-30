import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { PeriodFilterValue } from '../components/PeriodFilterSelect';
import type { MyReportCard } from '../types';

// 내 보고서 목록 (HAJA-366/#668, BE 연동 #844/HAJA-442). 실 다운로드/미리보기 연동은 후속(보고서 PDF
// GET /api/reports/{id}/pdf/{storageKey}는 실존하나 이번 스코프는 목록 UI까지).
export function useMyReports(period: PeriodFilterValue) {
  return useQuery<MyReportCard[], ApiError>({
    queryKey: ['mypage', 'reports', period],
    queryFn: () => mypageApi.getReports(period).then((res) => res.data),
  });
}
