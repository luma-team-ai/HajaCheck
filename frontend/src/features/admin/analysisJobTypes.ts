// 관리자 > AI 분석 현황 모니터링(신규) 도메인 타입 — 백엔드 GET /api/admin/analysis-jobs
// (AdminAnalysisJobItem/AdminAnalysisJobStatus)와 1:1로 맞춘다.
//
// jobId는 별도 "분석 실행" 테이블이 없어 inspection.id를 그대로 재사용한다(AiAnalysisStatusPage의
// "점검 ID" 표시와 동일한 결정 — 관리자 AI 분석 모니터링 설계 논의 참고).

// FAILED — 회차에 속한 사진 중 하나라도 AI 분석에 실패하면 ANALYZING에서 이 상태로 전이한다
// (백엔드 AdminAnalysisJobStatus 참고). COMPLETED와 분리된 별도 상태다.
export type AdminAnalysisJobStatus = 'PENDING' | 'ANALYZING' | 'FAILED' | 'COMPLETED';

export interface AdminAnalysisJob {
  jobId: number;
  facilityName: string;
  inspectorId: number;
  inspectorName: string;
  /** 점검일(inspections.inspection_date) — ISO date 문자열(YYYY-MM-DD) */
  inspectionDate: string;
  status: AdminAnalysisJobStatus;
  /** status=ANALYZING일 때만 값이 있을 수 있고, 그마저도 Redis 캐시가 없으면 null(fail-soft) */
  progressPercent: number | null;
}

export interface AdminAnalysisJobListParams {
  page: number;
  size: number;
  status?: AdminAnalysisJobStatus;
}
