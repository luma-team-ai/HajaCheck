import type { AnalysisJobStatus, ErrorLogLevel, ServerHealthStatus } from './monitoring.types';

export const MONITORING_EMPTY_CELL = '-';

export const SERVER_HEALTH_STATUS_LABEL: Record<ServerHealthStatus, string> = {
  HEALTHY: '정상',
  DEGRADED: '저하',
  DOWN: '중단',
};

export const SERVER_HEALTH_DOT_CLASS: Record<ServerHealthStatus, string> = {
  HEALTHY: 'bg-[#16a34a]',
  DEGRADED: 'bg-[#f97316]',
  DOWN: 'bg-danger',
};

export const JOB_STATUS_LABEL: Record<AnalysisJobStatus, string> = {
  IN_PROGRESS: '진행중',
  COMPLETED: '완료',
  FAILED: '실패',
  WAITING: '대기',
};

export const JOB_STATUS_BADGE_CLASS: Record<AnalysisJobStatus, string> = {
  IN_PROGRESS: 'bg-info-soft-bg text-info-soft-fg',
  COMPLETED: 'bg-[#dcfce7] text-[#16a34a]',
  FAILED: 'bg-danger-soft-bg text-danger',
  WAITING: 'bg-neutral-100 text-text-muted',
};

export const JOB_STATUS_DOT_CLASS: Record<AnalysisJobStatus, string> = {
  IN_PROGRESS: 'bg-info-soft-fg',
  COMPLETED: 'bg-[#16a34a]',
  FAILED: 'bg-danger',
  WAITING: 'bg-text-muted',
};

export const JOB_ID_TEXT_CLASS: Record<AnalysisJobStatus, string> = {
  IN_PROGRESS: 'text-heading',
  COMPLETED: 'text-heading',
  FAILED: 'text-danger',
  WAITING: 'text-heading',
};

export const ERROR_LOG_LEVEL_BADGE_CLASS: Record<ErrorLogLevel, string> = {
  ERROR: 'bg-danger-soft-bg text-danger',
  WARN: 'bg-warning-soft-bg text-warning-soft-fg',
};
