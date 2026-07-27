import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { AdminAnalysisJob, AdminAnalysisJobListParams } from '../analysisJobTypes';

// 관리자 API — 백엔드 GET /api/admin/analysis-jobs(신규) 실 연동.
// UI 페이지 상태는 adminApi.ts와 동일하게 1-base 관례를 쓰지만 백엔드 Pageable은 0-base(Spring
// 관례)라 여기서 한 번만 변환한다.
export const adminAnalysisJobApi = {
  getAnalysisJobs: (params: AdminAnalysisJobListParams) =>
    api.get<PageResponse<AdminAnalysisJob>>('/admin/analysis-jobs', {
      params: { ...params, page: params.page - 1 },
    }),
};
