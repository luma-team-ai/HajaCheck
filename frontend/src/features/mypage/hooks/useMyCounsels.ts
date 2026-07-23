import { useQuery } from '@tanstack/react-query';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import { MOCK_MY_COUNSELS_TOTAL_ELEMENTS, mockMyCounselRows } from '../mocks/myCounsels.mock';
import type { MyCounselRow } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

interface UseMyCounselsParams {
  page: number; // 1-base — TableFooterPagination/Pagination과 동일 관례
  size: number;
}

// 내 상담 내역 테이블 (HAJA-371, #678). 상담 BE API 전무(controller/service/repo가 .gitkeep 빈
// 스켈레톤, 엔티티만 존재) — useMyInspections와 완전히 동일한 패턴이다. page/size는 쿼리 키에는
// 반영하지만 실제 서버 페이징은 없다 — mock 폴백은 항상 같은 4건 + totalElements=18을 돌려준다.
// 후속 BE 연동 시 이 훅 시그니처(params)는 그대로 유지한 채 mypageApi.getCounsels만 실 서버로 붙이면 된다.
export function useMyCounsels(params: UseMyCounselsParams) {
  const fallback: PageResponse<MyCounselRow> = {
    content: mockMyCounselRows,
    page: params.page - 1,
    totalElements: MOCK_MY_COUNSELS_TOTAL_ELEMENTS,
  };

  return useQuery<PageResponse<MyCounselRow>, ApiError>({
    queryKey: ['mypage', 'counsels', 'list', params],
    queryFn: () =>
      fetchWithFallback(
        () => mypageApi.getCounsels(params).then((res) => res.data),
        fallback,
      ),
  });
}
