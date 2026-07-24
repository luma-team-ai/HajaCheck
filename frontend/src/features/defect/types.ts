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
  // "조치 결과 등록"(하자 상세 모달, HAJA-394/#726) 제출값 — 미등록이면 null/undefined.
  // 실제 저장 컬럼은 백엔드 Flyway V5 대기(TBD, docs/api-contract/contract.md §"하자 목록·상세 화면
  // 개편" 참고) — 필드가 옵셔널이라 기존 mock/테스트 데이터를 건드리지 않아도 된다.
  actionResult?: DefectActionResult | null;
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

// ---------------------------------------------------------------------------
// 하자 목록·상세 개편 (draft, HAJA-393/394 · #725/#726, 2026-07-24)
// docs/api-contract/contract.md §"하자 목록·상세 화면 개편" 참고 — 목록 화면은 하자 단건이 아니라
// 점검(Inspection) 단위로 재해석한다(사용자 확정 지시, 시각 디자인은 유지).
// ---------------------------------------------------------------------------

// 점검(회차) 진행 상태 — inspection feature의 InspectionStatus와 동일한 백엔드 enum 값이지만
// feature 간 직접 import 금지(React_코드_컨벤션.md §1)로 로컬 재정의한다.
export type InspectionStatus = 'CREATED' | 'UPLOADING' | 'ANALYZING' | 'ANALYZED' | 'REVIEWED' | 'REPORTED';

export const INSPECTION_STATUS_LABEL: Record<InspectionStatus, string> = {
  CREATED: '생성됨',
  UPLOADING: '업로드중',
  ANALYZING: '분석중',
  ANALYZED: '분석완료',
  REVIEWED: '검수완료',
  REPORTED: '보고완료',
};

// 등급별 하자 건수 분포 — 점검 목록 테이블의 "등급분포" 컬럼(contract.md 화면 구조 ①)
export type InspectionGradeDistribution = Record<DefectGrade, number>;

// GET /api/inspections 목록 항목 — 신규 엔드포인트(BE 미구현, MSW 목으로 우선 개발, contract.md 참고)
export interface InspectionListItem {
  id: number;
  facilityId: number;
  facilityName: string;
  facilityType: string;
  roundNo: number;
  inspectionDate: string; // YYYY-MM-DD
  status: InspectionStatus;
  defectCount: number;
  gradeDistribution: InspectionGradeDistribution;
  assigneeName: string | null;
}

// GET /api/inspections 쿼리 파라미터 — page는 Spring Data 관례대로 0-based
export interface InspectionListFilters {
  status?: InspectionStatus;
  facilityId?: number;
  page?: number;
  size?: number;
}

// 점검 목록 필터의 시설물 select 옵션 — GET /api/facilities 재사용(§api 레이어 참고)
export interface InspectionFacilityOption {
  id: number;
  name: string;
}

// 담당자 select 옵션 — facility feature의 FacilityAssignableUser와 동일 모양이지만 feature 간
// 직접 import 금지 컨벤션에 따라 로컬로 재정의한다(#690 GET /api/facilities/assignable-users 재사용 대상).
export interface DefectAssignee {
  id: number;
  name: string;
}

// 하자 상세 모달 "조치 결과 등록" 결과 — 등록되면 Defect.actionResult에 채워진다.
export interface DefectActionResult {
  actionContent: string;
  actionDate: string; // YYYY-MM-DD
  assigneeId: number;
  assigneeName: string;
  afterPhotoUrl: string | null;
}

// 하자 상세 모달 "조치 완료 등록" 제출 body — PATCH /api/defects/{id}/status 확장 가정(BE 판단 대기,
// contract.md §"조치 결과 등록" 참고). 실제 필드명/엔드포인트가 BE 확정과 다르면 PR에
// [CONTRACT-CHANGE-REQUEST]로 표시할 것.
export interface DefectActionSubmitRequest {
  status: 'RESOLVED';
  actionContent: string;
  actionDate: string;
  assigneeId: number;
  afterMediaId?: number;
}
