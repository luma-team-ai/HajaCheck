import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/reportApi';
import { mockReportFacilityOptions } from '../mocks/reportList.mock';

// 보고서 목록 필터의 시설물 select 옵션 — GET /api/facilities 재사용.
// 실 백엔드 호출 실패/404 시에도 프론트엔드 테스트를 위해 목 데이터로 폴백한다.
export function useReportFacilityOptions() {
  return useQuery({
    queryKey: ['report', 'facility-options'] as const,
    queryFn: async ({ signal }) => {
      try {
        const res = await reportApi.listFacilityOptions(signal);
        if (Array.isArray(res.data) && res.data.length > 0) {
          return res.data;
        }
      } catch (err) {
        console.warn('[useReportFacilityOptions] API 호출 실패 — 하이브리드 목 폴백 사용:', err);
      }
      return mockReportFacilityOptions;
    },
  });
}
