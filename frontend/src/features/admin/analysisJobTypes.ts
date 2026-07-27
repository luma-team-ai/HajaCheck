// 관리자 > AI 분석 현황 모니터링(신규) 도메인 타입 — 백엔드 GET /api/admin/analysis-jobs
// (AdminAnalysisJobItem/AdminAnalysisJobStatus)와 1:1로 맞춘다.
//
// jobId는 별도 "분석 실행" 테이블이 없어 inspection.id를 그대로 재사용한다(AiAnalysisStatusPage의
// "점검 ID" 표시와 동일한 결정 — 관리자 AI 분석 모니터링 설계 논의 참고).

// 실패(FAILED) 상태는 없다 — InspectionStatus 자체에 FAILED가 없고, 분석 실패 시 ANALYZING이
// PENDING 버킷(CREATED/UPLOADING)으로 롤백된다. 그래서 PENDING은 "아직 시작 안 함"과 "직전 시도
// 실패로 대기 복귀"를 구분하지 못한다(백엔드 AdminAnalysisJobStatus 주석과 동일한 한계).
export type AdminAnalysisJobStatus = 'PENDING' | 'ANALYZING' | 'COMPLETED';

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
