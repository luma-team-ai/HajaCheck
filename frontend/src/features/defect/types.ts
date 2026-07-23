// 하자 목록/상세 조회 — HAJA-30, backend DefectResponse/PageResponse<DefectResponse>와 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — map/dashboard의 등급 라벨과 별개로 로컬 정의

export type DefectType = 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE';
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type DefectStatus = 'DETECTED' | 'CONFIRMED' | 'ACTION_PENDING' | 'IN_PROGRESS' | 'RESOLVED';

export interface Defect {
  id: number;
  inspectionId: number;
  facilityId: number;
  facilityName: string;
  facilityType: string;
  type: DefectType;
  typeLabel: string;
  grade: DefectGrade | null;
  status: DefectStatus;
  confidence: number;
  reviewed: boolean;
  bboxX: number | null;
  bboxY: number | null;
  bboxW: number | null;
  bboxH: number | null;
  crackWidthMm: number | null;
  crackLengthMm: number | null;
  // 인가된 /api/media/{id}/thumbnail 경로 — mediaId가 없으면 null(HAJA-314)
  imageUrl: string | null;
  createdAt: string;
}

// GET /api/defects/{id}/revisions 응답 항목 — backend DefectRevisionResponse와 1:1(HAJA-314)
export interface DefectRevision {
  id: number;
  revisedBy: number;
  fieldChanged: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string | null;
  createdAt: string;
}

// GET /api/defects 쿼리 파라미터 — page는 Spring Data 관례대로 0-based
export interface DefectListFilters {
  type?: DefectType;
  grade?: DefectGrade;
  status?: DefectStatus;
  page?: number;
  size?: number;
}

// 하자 유형 한글 라벨(DDL 코멘트 기준) — 백엔드 DefectResponse.typeLabel과 동일 매핑.
// 목록 필터 드롭다운은 실제 하자 데이터를 아직 안 불러온 상태에서도 옵션을 보여줘야 해서
// typeLabel(응답 필드)만으로는 부족해 로컬로도 유지한다.
export const DEFECT_TYPE_LABEL: Record<DefectType, string> = {
  CRACK: '균열',
  SPALLING: '박리·박락',
  LEAK_EFFLORESCENCE: '누수·백태',
  REBAR_EXPOSURE: '철근 노출',
  PAINT_DAMAGE: '도장 손상',
};

// 결함 조치 상태 한글 라벨(DDL 코멘트 기준 — backend DefectStatus.java 주석과 동일 매핑)
export const DEFECT_STATUS_LABEL: Record<DefectStatus, string> = {
  DETECTED: '탐지됨',
  CONFIRMED: '확인됨',
  ACTION_PENDING: '조치대기',
  IN_PROGRESS: '조치중',
  RESOLVED: '해결됨',
};

// 등급 한글 라벨 — features/map/constants.ts GRADE_LABEL과 동일 값(feature 간 직접 import 금지로 로컬 재정의)
export const DEFECT_GRADE_LABEL: Record<DefectGrade, string> = {
  A: '양호',
  B: '경미',
  C: '주의',
  D: '경고',
  E: '중대',
};
