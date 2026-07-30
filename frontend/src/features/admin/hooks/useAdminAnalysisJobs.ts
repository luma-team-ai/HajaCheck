import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { adminAnalysisJobApi } from '../api/adminAnalysisJobApi';
import type { AdminAnalysisJob, AdminAnalysisJobListParams } from '../analysisJobTypes';

// AI 분석 현황 목록 조회 — 상태 필터·페이지가 바뀔 때마다 새 쿼리 키로 재조회한다.
// keepPreviousData: 페이지 이동 시 표가 빈 화면으로 깜빡이지 않고 이전 페이지를 유지한 채 갱신된다.
// useAdminUsers와 동일 패턴 — 목 폴백은 두지 않는다(개발 환경은 MSW가 응답, 그 밖의 실패는 화면이
// 에러 상태로 정직하게 노출).
export function useAdminAnalysisJobs(params: AdminAnalysisJobListParams) {
  return useQuery<PageResponse<AdminAnalysisJob>, ApiError>({
    queryKey: ['admin', 'analysis-jobs', params],
    queryFn: () => adminAnalysisJobApi.getAnalysisJobs(params).then((res) => res.data),
    placeholderData: keepPreviousData,
  });
}
