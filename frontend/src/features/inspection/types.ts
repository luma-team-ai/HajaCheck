// FR-4 결과 시각화·검수 — PRD §5 FR-4, §6.3 데이터 모델(안) 기준
// 탐지 클래스 3종 확정(PRD v0.42, 2026-07-13) — 누수백태·도장손상은 데이터 확보 상황상 범위 제외
export type DefectType = '균열' | '박리박락' | '철근노출';
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type DefectStatus = 'DETECTED' | 'CONFIRMED' | 'ACTION_PENDING' | 'IN_PROGRESS' | 'RESOLVED';

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
  // 정량 실측은 유형별로 다르다(하자_심각도_등급_규칙.md §3.2) — 균열은 선형(폭·길이),
  // 박리박락·철근노출은 면적형이라 실측 mm가 아니라 마스크 면적 비율을 쓴다.
  widthMm?: number; // 균열 폭(균열 전용)
  lengthMm?: number; // 균열 길이(균열 전용)
  areaRatio?: number; // 마스크 면적 비율 0~1(박리박락·철근노출 전용)
  summary: string; // 분석 요약
}

export interface InspectionMedia {
  id: number;
  imageUrl: string;
  width: number;
  height: number;
}

// 촬영 데이터 업로드 — API 명세서 v0.3 AP-005, POST /api/inspections/{id}/media.
// backend MediaResponse와 1:1(originalUrl은 의도적으로 없음 — 원본 비공개 정책).
export interface Media {
  id: number;
  inspectionId: number;
  fileType: 'IMAGE' | 'VIDEO';
  thumbnailUrl: string;
  mimeType: string;
  capturedAt: string | null;
  gpsLat: number | null;
  gpsLng: number | null;
  createdAt: string;
}

export interface InspectionResult {
  inspectionId: number;
  media: InspectionMedia;
  defects: Defect[];
  defectCode: string; // 예: DEF-0192
  facilityName: string; // 예: 강남 오피스타워 A동
  facilityType: string; // 예: 건물
  status: string; // 예: AI 검수중
  reviewedCount: number; // 예: 128
  totalCount: number; // 예: 214
}

// 점검(회차) 생성 — API 명세서 v0.3 AP-004, POST /api/inspections.
// backend InspectionCreateRequest(facilityId/inspectionDate/assignedInspectorId)와 1:1.
export interface InspectionCreateRequest {
  facilityId: number;
  /** YYYY-MM-DD */
  inspectionDate: string;
  assignedInspectorId: number;
}

// backend InspectionResponse와 1:1
export interface InspectionCreateResponse {
  id: number;
  facilityId: number;
  createdBy: number;
  assignedInspectorId: number;
  roundNo: number;
  inspectionDate: string;
  status: string;
  createdAt: string;
}

// 시설물 선택 셀렉트 전용 — feature 간 직접 import 금지(React_코드_컨벤션.md §1)라
// facility feature의 Facility 타입을 재사용하지 않고 이 화면에 필요한 필드만 로컬로 정의한다.
export interface FacilityOption {
  id: number;
  name: string;
}

// 분석 결과 뷰어(useInspectionResultReal)가 GET /api/facilities/{id}로 조회하는 시설물 상세 —
// 위 FacilityOption과 같은 이유로 로컬 정의(cross-feature import 금지).
export interface FacilityDetail {
  id: number;
  name: string;
  type: string;
  address: string | null;
  builtYear: number | null;
  scale: string | null;
  nextInspectionDueAt: string | null;
}
