import type { AdminAnalysisJob } from '../analysisJobTypes';

// AI 분석 현황 모니터링 예제 데이터 — PENDING/ANALYZING/COMPLETED 세 상태를 모두 포함해 화면
// 필터·배지·진행률 렌더를 로컬에서 확인할 수 있게 한다. 전부 example.com 합성값(실데이터 아님).
export const mockAnalysisJobs: AdminAnalysisJob[] = [
  {
    jobId: 101,
    facilityName: '강남 오피스빌딩',
    inspectorId: 1,
    inspectorName: '김지수',
    inspectionDate: '2026-07-27',
    status: 'ANALYZING',
    progressPercent: 42,
  },
  {
    jobId: 98,
    facilityName: '역삼 지식산업센터',
    inspectorId: 2,
    inspectorName: '박진우',
    inspectionDate: '2026-07-26',
    status: 'COMPLETED',
    progressPercent: null,
  },
  {
    jobId: 95,
    facilityName: '판교 데이터센터',
    inspectorId: 1,
    inspectorName: '김지수',
    inspectionDate: '2026-07-25',
    status: 'COMPLETED',
    progressPercent: null,
  },
  {
    jobId: 102,
    facilityName: '서초 주상복합',
    inspectorId: 3,
    inspectorName: '최서준',
    inspectionDate: '2026-07-27',
    status: 'PENDING',
    progressPercent: null,
  },
];
