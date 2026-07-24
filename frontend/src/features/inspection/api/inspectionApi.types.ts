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
