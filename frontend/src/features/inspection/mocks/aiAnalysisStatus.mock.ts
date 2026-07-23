import type { AnalysisFileStatus, AnalysisStage } from '../api/inspectionApi.types';

export interface AnalysisFileRow {
  id: string;
  fileName: string;
  status: AnalysisFileStatus;
  defectCount: number | null;
  elapsedOrEta: string;
}

export interface SeverityDistributionEntry {
  grade: 'A' | 'B' | 'C' | 'D' | 'E';
  percentage: number;
  color: string;
}

export interface AiAnalysisStatus {
  jobId: string | null;
  progressPercent: number;
  totalFileCount: number;
  analyzedFileCount: number;
  estimatedRemainingMinutes: number | null;
  currentStage: AnalysisStage;
  files: AnalysisFileRow[];
  detectedDefectCount: number;
  riskyProgressiveCrackCount: number;
  severityDistribution: SeverityDistributionEntry[];
  failedCount: number;
}

// AI 분석 실행/상태 사이드바 직접 진입(:id 없는 정적 경로) 전용 — 실제 잡이 없으므로 항상 빈
// 상태다. 점검 생성 화면을 거친 실제 분석은 이제 useAnalysisStatus(백엔드 폴링, dev-05-04)를 쓴다.
export function buildEmptyAnalysisStatus(): AiAnalysisStatus {
  return {
    jobId: null,
    progressPercent: 0,
    totalFileCount: 0,
    analyzedFileCount: 0,
    estimatedRemainingMinutes: null,
    currentStage: 'upload',
    files: [],
    detectedDefectCount: 0,
    riskyProgressiveCrackCount: 0,
    severityDistribution: [],
    failedCount: 0,
  };
}
