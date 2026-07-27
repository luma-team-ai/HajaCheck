import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import { mockAnalysisJobs } from '../mocks/analysisJobs.mock';
import type { AdminAnalysisJob, AdminAnalysisJobStatus } from '../analysisJobTypes';

const DEFAULT_PAGE_SIZE = 10;
const VALID_STATUSES = new Set<AdminAnalysisJobStatus>(['PENDING', 'ANALYZING', 'COMPLETED']);

function parseStatusParam(value: string | null): AdminAnalysisJobStatus | null {
  return value !== null && VALID_STATUSES.has(value as AdminAnalysisJobStatus)
    ? (value as AdminAnalysisJobStatus)
    : null;
}

// 백엔드 GET /api/admin/analysis-jobs(신규) 구현 완료 — 이 핸들러는 VITE_ENABLE_MSW=false로 끄지
// 않은 로컬 개발/테스트에서만 쓰이는 목 폴백이다(adminApi.handlers.ts와 동일 컨벤션).
// page 파라미터는 실 백엔드와 동일하게 0-base(adminAnalysisJobApi.ts가 UI의 1-base 상태를 여기서
// 변환해 보낸다).
export const adminAnalysisJobHandlers = [
  http.get('/api/admin/analysis-jobs', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? DEFAULT_PAGE_SIZE);
    const status = parseStatusParam(url.searchParams.get('status'));

    const filtered = mockAnalysisJobs.filter((job) => !status || job.status === status);

    const start = page * size;
    const body: ApiResponse<PageResponse<AdminAnalysisJob>> = {
      success: true,
      data: {
        content: filtered.slice(start, start + size),
        page,
        totalElements: filtered.length,
      },
    };
    return HttpResponse.json(body);
  }),
];
