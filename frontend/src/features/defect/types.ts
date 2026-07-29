// 하자 목록/상세 조회 — HAJA-30, backend DefectResponse/PageResponse<DefectResponse>와 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — map/dashboard의 등급 라벨과 별개로 로컬 정의

export type DefectType = 'CRACK' | 'SPALLING' | 'LEAK_EFFLORESCENCE' | 'REBAR_EXPOSURE' | 'PAINT_DAMAGE';
export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type DefectStatus = 'DETECTED' | 'CONFIRMED' | 'IN_PROGRESS' | 'RESOLVED';

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
  // 백엔드 DefectResponse는 이 값을 중첩 객체가 아니라 actionPhotoUrl/actionContent/actionDate/
  // actionAssigneeId/actionAssigneeName flat 필드로 응답한다 — normalizeDefect()가 API 경계에서
  // 이 형태로 조립한다(#1182). 필드가 옵셔널이라 기존 mock/테스트 데이터를 건드리지 않아도 된다.
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

// #893 방어 코드 — 백엔드 응답에 gradeDistribution이 없거나(계약 불일치) 필드명이 다를 때
// "Cannot read properties of undefined" 크래시를 막기 위한 런타임 기본값. InspectionListItem
// 타입 자체는 필수 필드로 유지하고, 이 상수는 소비 지점(InspectionTable 등)에서 ?? 폴백으로만 쓴다.
export const EMPTY_GRADE_DISTRIBUTION: InspectionGradeDistribution = { A: 0, B: 0, C: 0, D: 0, E: 0 };

// 점검 유형(정기/정밀/긴급) — 백엔드 inspection_type PG enum(REGULAR/DETAILED/EMERGENCY)과 동일 값.
// #540 백엔드 갭 대응 — Inspection 엔티티/DB에는 이미 있었으나 응답 DTO에 노출되지 않던 필드를
// InspectionListItemResponse.type으로 노출하면서 프론트도 함께 계약을 맞춘다.
export type InspectionType = 'REGULAR' | 'DETAILED' | 'EMERGENCY';

export const INSPECTION_TYPE_LABEL: Record<InspectionType, string> = {
  REGULAR: '정기',
  DETAILED: '정밀',
  EMERGENCY: '긴급',
};

// GET /api/inspections 목록 항목 — 백엔드 PR #891로 origin/dev 머지 완료(defectApi.ts 참고).
// type 필드는 #540(백엔드 갭 대응)으로 추가 — 기존 컬럼을 응답 DTO에 새로 노출한 것뿐이다.
export interface InspectionListItem {
  id: number;
  facilityId: number;
  facilityName: string;
  facilityType: string;
  roundNo: number;
  inspectionDate: string; // YYYY-MM-DD
  type: InspectionType;
  status: InspectionStatus;
  defectCount: number;
  gradeDistribution: InspectionGradeDistribution;
  assigneeName: string | null;
}

// GET /api/inspections 쿼리 파라미터 — page는 Spring Data 관례대로 0-based. inspectionStatus는
// 클라이언트 내부 이름이며 API 레이어에서 반복 쿼리 키 status로 명시 변환한다.
// defectType/defectGrade/defectStatus(#878/HAJA-452, 백엔드 PR #891로 origin/dev 머지 완료)는 자연어
// 하자조건 검색(POST /api/defects/nl-search)이 산출한 필터를 그대로 실어 재조회하는 용도 — 셋 중
// 1개 이상 주어지면 같은 하자 하나가 조건을 전부 만족하는 점검만 반환한다(EXISTS 서브쿼리, 서로 다른
// 하자로 나눠 만족하면 매칭 아님. InspectionController.list 설명 참고).
export interface InspectionListFilters {
  inspectionType?: InspectionType[];
  inspectionStatus?: InspectionStatus[];
  inspectionDateFrom?: string;
  inspectionDateTo?: string;
  roundNoMin?: number;
  roundNoMax?: number;
  defectCountMin?: number;
  defectCountMax?: number;
  facilityId?: number;
  defectType?: DefectType[];
  defectGrade?: DefectGrade[];
  defectStatus?: DefectStatus[];
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

// 하자 상세 모달 "상태 저장" 제출 body — PATCH /api/defects/{id}/action, DefectActionResultRequest
// 1:1(contract.md §"조치 결과 등록" 확정, #726). targetStatus(#1128) — "진행상태" select 값으로,
// 이제 IN_PROGRESS(조치중)/RESOLVED(조치완료) 중 백엔드가 실제로 전이한 상태를 명시적으로 보낸다
// (과거엔 항상 RESOLVED로만 전이해 이 필드가 없었음). targetStatus가 현재 상태와 같은 IN_PROGRESS는
// (#1193/HAJA-569) 상태를 유지한 채 "조치중" 사진을 시간차를 두고 추가 등록하는 재제출을 의미한다.
export interface DefectActionSubmitRequest {
  actionContent: string;
  actionDate: string;
  actionAssigneeId: number;
  actionMediaId: number;
  targetStatus: 'IN_PROGRESS' | 'RESOLVED';
}

// 조치 등록 제출 이력 1건 — GET /api/defects/{id}/action-logs?phase=, backend
// DefectActionLogResponse와 1:1(#1193/HAJA-569 백엔드, #1211/HAJA-574 프론트). phase(IN_PROGRESS는
// "조치 사진" 탭, RESOLVED는 "조치 완료 사진" 탭)별로 여러 건 존재할 수 있어 하자 상세 모달에서
// 등록일 select로 넘겨보기 위한 목록 조회 전용 타입이다.
export interface DefectActionLogEntry {
  id: number;
  photoUrl: string | null;
  actionContent: string;
  actionDate: string; // YYYY-MM-DD
  actionAssigneeId: number;
  actionAssigneeName: string | null;
  createdAt: string;
}

export type DefectActionLogPhase = 'IN_PROGRESS' | 'RESOLVED';
