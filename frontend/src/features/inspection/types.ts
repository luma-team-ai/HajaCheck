// FR-4 결과 시각화·검수 — PRD §5 FR-4, §6.3 데이터 모델(안) 기준
export type DefectType = '균열' | '박리박락' | '누수백태' | '철근노출' | '도장손상';
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type DefectStatus = '신규' | '검수확정' | '조치대기' | '조치중' | '조치완료';

export interface DefectBoundingBox {
  x: number; // 0~1 정규화 좌표 (이미지 너비 기준)
  y: number;
  width: number;
  height: number;
}

export interface Defect {
  id: number;
  type: DefectType;
  grade: DefectGrade;
  status: DefectStatus;
  confidence: number; // 0~1
  bbox: DefectBoundingBox;
  depthMm: number; // 예상 깊이
  widthMm: number; // 균열 폭
  summary: string; // 분석 요약
}

export interface InspectionMedia {
  id: number;
  imageUrl: string;
  width: number;
  height: number;
}

export interface InspectionResult {
  inspectionId: number;
  media: InspectionMedia;
  defects: Defect[];
  defectCode: string; // 예: DEF-0192
  facilityName: string; // 예: 강남 오피스타워 A동
  status: string; // 예: AI 검수중
  reviewedCount: number; // 예: 128
  totalCount: number; // 예: 214
}
