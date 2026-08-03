// 플랫폼 관리자 > 시스템 모니터링(#729/#728) 도메인 타입. Figma node-id 1-404.
// 백엔드 /api/platform-admin/monitoring 실제 구현(#728)과 1:1 계약.
// 분석 잡 큐 실데이터(#1408)는 inspections 테이블 기준으로 채워진다.

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
  /** 시설물 위치(facilities.address) — 시설물명이 아니라 주소 텍스트 */
  facilityAddress: string;
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

// 서버 자원 사용률(#728) — 백엔드가 Actuator 메트릭으로 조회한 현재 시점 값이라 시계열이 아니다(HF API
// 사용량 카드를 대체 — HF는 사용량 조회 공개 API가 없어 별도 과업으로 분리).
export interface ServerResourceUsage {
  /** CPU 사용률(0~100) */
  cpuUsagePercent: number;
  /** JVM 힙 메모리 사용률(0~100) */
  memoryUsagePercent: number;
  /** 디스크 사용률(0~100) */
  diskUsagePercent: number;
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
  /** inspections 테이블 기준 실데이터(#1408) — 회차가 하나도 없으면 summary 전부 0, jobs 빈 배열 */
  jobQueue: AnalysisJobQueue;
  resourceUsage: ServerResourceUsage;
  errorLogs: ErrorLogItem[];
}
