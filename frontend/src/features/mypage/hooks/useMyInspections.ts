import { useQuery } from '@tanstack/react-query';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { PeriodFilterValue } from '../components/PeriodFilterSelect';
import type { InspectionHistoryRow } from '../types';

interface UseMyInspectionsParams {
  page: number; // 1-base — TableFooterPagination/Pagination과 동일 관례(아래 -1 변환 참고)
  size: number;
  period: PeriodFilterValue;
}

// 내 점검 이력 테이블 (HAJA-366/#668, BE 연동 #844/HAJA-442). page는 1-base UI 값을 그대로 받아
// BE 0-base 규약(InspectionController.list와 동일 page 규약)에 맞춰 -1 해서 전달한다.
export function useMyInspections(params: UseMyInspectionsParams) {
  return useQuery<PageResponse<InspectionHistoryRow>, ApiError>({
    queryKey: ['mypage', 'inspections', 'list', params],
    queryFn: () =>
      mypageApi
        .getInspections({ page: params.page - 1, size: params.size, period: params.period })
        .then((res) => res.data),
  });
}
