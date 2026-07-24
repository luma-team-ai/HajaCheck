// Backend API response types — DO NOT import from ../types (that's for component state)
export type DefectType = '균열' | '박리박락' | '누수·백태' | '철근노출' | '도장 손상';
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type DefectStatus = 'DETECTED' | 'CONFIRMED' | 'ACTION_PENDING' | 'IN_PROGRESS' | 'RESOLVED';

export interface DefectDetailItem {
  id: number;
  inspectionId: number;
  type: DefectType;
  grade: DefectGrade;
  status: DefectStatus;
  confidence: number;
  isReviewed: boolean;
  bboxX: number | null;
  bboxY: number | null;
  bboxW: number | null;
  bboxH: number | null;
  crackWidthMm?: number;
  crackLengthMm?: number;
  createdAt: string; // ISO datetime
  mediaId?: number | null; // 이미지 ID — 백엔드에서 제공(#777 계약)
  imageUrl?: string | null; // 이미지 URL 형식: /api/media/{mediaId}/thumbnail — 백엔드에서 제공(#777 계약)
}

// 실제 백엔드가 받는 영문 enum 값 그대로 — 위 DefectType(한글 리터럴)과 다르게 선언한 이유는
// 그 타입이 실제 백엔드 직렬화 값과 불일치하는 기존 버그이기 때문(이번 스코프 밖, 손대지 않음).
export interface DefectCreateRequest {
  type: 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE';
  grade: DefectGrade;
}

export type InspectionStatus = 'CREATED' | 'UPLOADING' | 'ANALYZING' | 'ANALYZED' | 'REVIEWED' | 'REPORTED';

export interface InspectionResponse {
  id: number;
  facilityId: number;
  createdBy: number;
  assignedInspectorId: number;
  roundNo: number;
  inspectionDate: string; // YYYY-MM-DD
  status: InspectionStatus;
  createdAt: string; // ISO datetime
}

// AI 분석 실행/상태(dev-05-04) — backend AnalysisStatusResponse와 필드명 그대로 대응(camelCase, Jackson 기본).
// 'failed'(코드 리뷰 P2) — 워커가 이미지 전체 실패로 롤백할 때만 쓰는 종료 상태. 'done'과 마찬가지로
// 폴링을 멈춰야 한다(useAnalysisStatus 참고) — 안 그러면 실패한 잡이 영원히 "진행 중 0%"로 보인다.
export type AnalysisStage = 'upload' | 'frameExtraction' | 'aiDetection' | 'postProcessing' | 'done' | 'failed';
export type AnalysisFileStatus = 'waiting' | 'analyzing' | 'completed' | 'failed';

export interface AnalysisFileProgress {
  mediaId: number;
  fileName: string;
  status: AnalysisFileStatus;
  defectCount: number | null;
  elapsedOrEta: string;
}

export interface AnalysisStatusResponse {
  inspectionId: number;
  stage: AnalysisStage;
  progressPercent: number;
  totalFileCount: number;
  analyzedFileCount: number;
  files: AnalysisFileProgress[];
  detectedDefectCount: number;
  riskyCrackCount: number;
  severityDistribution: Record<'A' | 'B' | 'C' | 'D' | 'E', number>;
  failedCount: number;
}
