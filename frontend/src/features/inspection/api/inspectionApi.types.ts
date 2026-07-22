// Backend API response types — DO NOT import from ../types (that's for component state)
export type DefectType = '균열' | '박리박락' | '철근노출';
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type DefectStatus = '신규' | '검수확정' | '조치대기' | '조치중' | '조치완료';

export interface DefectDetailItem {
  id: number;
  inspectionId: number;
  type: DefectType;
  grade: DefectGrade;
  status: DefectStatus;
  confidence: number;
  isReviewed: boolean;
  bboxX: number;
  bboxY: number;
  bboxW: number;
  bboxH: number;
  crackWidthMm?: number;
  crackLengthMm?: number;
  createdAt: string; // ISO datetime
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
