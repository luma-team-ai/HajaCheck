// 플랫폼 관리자 > 시스템 모니터링(#729) 도메인 타입. Figma node-id 1-404.
// 백엔드 /api/platform-admin/monitoring 계약 확정 전까지 MSW 목데이터로만 채운다(BE 이슈 별도).

export type ServerHealthStatus = 'HEALTHY' | 'DEGRADED' | 'DOWN';

export interface ServerHealthItem {
  id: string;
  name: string;
  status: ServerHealthStatus;
  /** 부가 지표(가동률 등) — 없으면 표시하지 않는다 */
  metric?: string;
}

export type AnalysisJobStatus = 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'WAITING';

export interface AnalysisJobQueueItem {
  id: string;
  facilityName: string;
  imageCount: number;
  status: AnalysisJobStatus;
  /** 소요 시간(mm:ss) — 대기 상태 등 아직 소요 시간이 없으면 null */
  durationLabel: string | null;
  /** 잡 등록 시각("YYYY-MM-DD HH:mm:ss") — 최신 1일치 필터링(filterToLatestDay)에 사용 */
  recordedAt: string;
}

export interface AnalysisJobQueueSummary {
  inProgress: number;
  completed: number;
  failed: number;
}

export interface AnalysisJobQueue {
  summary: AnalysisJobQueueSummary;
  jobs: AnalysisJobQueueItem[];
}

export interface HfApiWeeklyUsagePoint {
  day: string;
  usage: number;
}

export interface HfApiUsage {
  weeklyUsage: HfApiWeeklyUsagePoint[];
  /** 이번 주 예산 사용률(0~100) */
  budgetUsedPercent: number;
  /** 예산 경고 한도(0~100) — 사용률 바 위에 마커로 표시 */
  budgetLimitPercent: number;
  /** 한도 초과 시 정책 안내 — 없으면 배너를 표시하지 않는다 */
  warningMessage: string | null;
}

export type ErrorLogLevel = 'ERROR' | 'WARN';

export interface ErrorLogItem {
  id: string;
  timestamp: string;
  level: ErrorLogLevel;
  service: string;
  message: string;
}

export interface SystemMonitoringResponse {
  serverHealth: ServerHealthItem[];
  jobQueue: AnalysisJobQueue;
  hfApiUsage: HfApiUsage;
  errorLogs: ErrorLogItem[];
}
