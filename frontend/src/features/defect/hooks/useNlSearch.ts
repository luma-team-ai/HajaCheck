import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import type { NlSearchResult } from '../nlSearchTypes';

// AI 실패가 하자 목록의 비-AI 기능(수동 필터)을 막지 않아야 함 — 이 훅의 에러는 DefectFilterBar
// 내부에서만 처리하고, 실패해도 기존 수동 필터 상태는 그대로 유지된다(§4.4 fallback 원칙).
export function useNlSearch() {
  const mutation = useMutation<NlSearchResult, ApiError, string>({
    mutationFn: (query) => defectApi.nlSearch(query).then((res) => res.data),
  });

  return {
    search: mutation.mutate,
    data: mutation.data,
    error: mutation.error,
    isPending: mutation.isPending,
    reset: mutation.reset,
  };
}
