import { useQuery } from '@tanstack/react-query';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import {
  MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
  mockMyInspectionRows,
} from '../mocks/myInspections.mock';
import type { InspectionHistoryRow } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

interface UseMyInspectionsParams {
  page: number; // 1-base — TableFooterPagination/Pagination과 동일 관례(useMyInspections 호출부 참고)
  size: number;
}

// 내 점검 이력 테이블 (HAJA-366, #668). BE 미구현이라 page/size는 쿼리 키에는 반영하지만
// 실제 서버 페이징은 없다 — mock 폴백은 항상 같은 8건 + totalElements=18을 돌려준다(mock 파일 주석 참고).
// 후속 BE 연동 시 이 훅 시그니처(params)는 그대로 유지한 채 mypageApi.getInspections만 실 서버로 붙으면 된다.
export function useMyInspections(params: UseMyInspectionsParams) {
  const fallback: PageResponse<InspectionHistoryRow> = {
    content: mockMyInspectionRows,
    page: params.page - 1,
    totalElements: MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
  };

  return useQuery<PageResponse<InspectionHistoryRow>, ApiError>({
    queryKey: ['mypage', 'inspections', 'list', params],
    queryFn: () =>
      fetchWithFallback(
        () => mypageApi.getInspections(params).then((res) => res.data),
        fallback,
      ),
  });
}
