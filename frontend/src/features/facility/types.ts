// 시설물 목록/등록 — dev-04-01(Figma), FR-003 — backend PR #176(dev 머지) 계약과 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — 대시보드 타입과 별개로 로컬 정의

export interface Facility {
  id: number;
  name: string;
  type: string;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  builtYear: number | null;
  scale: string | null;
  inspectionCycleMonths: number | null;
  // ISO date — 클라이언트가 산정해 전송, 서버(FacilityService)는 그대로 저장하는 패스스루 필드다.
  // 서버측 자동산정은 FR-019 `POST /api/facilities/{id}/schedule`(dev-04-03) 소관.
  nextInspectionDueAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateFacilityRequest {
  name: string;
  type: string;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  builtYear?: number | null;
  scale?: string | null;
  inspectionCycleMonths?: number | null;
  // 클라이언트가 inspectionCycleMonths 기준으로 산정해 전송(utils/computeNextInspectionDueAt.ts) —
  // 백엔드가 자동계산하지 않으므로 FE가 보내지 않으면 항상 null로 저장된다.
  nextInspectionDueAt?: string | null;
}

// 점검 주기 설정(dev-04-03, FR-019) — POST /api/facilities/{id}/schedule 실 계약
export interface SetFacilityScheduleRequest {
  inspectionCycleMonths: number;
}

export interface SetFacilityScheduleResponse {
  nextInspectionDueAt: string | null;
}

// 전체 시설물 점검 주기 현황 — 우측 테이블 전용 타입.
// name/cycleMonths/nextInspectionDueAt은 Facility 실필드와 매핑 가능하지만,
// type(점검유형 정기/정밀/긴급)·lastInspectedAt·assigneeName은 백엔드 계약에 없어 MSW 목 전용이다.
// 실연동 시 Facility 계약 확장 필요(핸드오프 §8 — [CONTRACT-CHANGE-REQUEST]로 A에 요청 예정).
export type InspectionCycleType = '정기' | '정밀' | '긴급';

export interface InspectionCycleStatusRow {
  id: number;
  name: string;
  type: InspectionCycleType;
  cycleMonths: number;
  /** YYYY-MM-DD — 목 전용(백엔드 미보유) */
  lastInspectedAt: string;
  /** YYYY-MM-DD */
  nextInspectionDueAt: string;
  /** 목 전용(백엔드 미보유) */
  assigneeName: string;
}

// 시설물 상세(Figma "hajaCheck Facility Detail") — 등급/점검 이력 집계는 백엔드에 아직 없어
// (점검·하자 모듈 목록/집계 엔드포인트 미구현) feature 로컬 목 전용. Facility 실 계약과는 별개 타입.
export type DefectGradeLetter = 'A' | 'B' | 'C' | 'D' | 'E';

export interface FacilityInspectionHistoryItem {
  id: number;
  roundNo: number;
  /** YYYY-MM-DD */
  inspectionDate: string;
  inspectorName: string;
  status: '검수완료' | '완료' | '진행중';
  imageCount: number;
  defectGradeBreakdown: { grade: DefectGradeLetter; count: number }[];
  /** 이전 회차 대비 변화 메모 — 최신 회차에만 존재 */
  changeNote?: string;
  /** 썸네일 미리보기 외 나머지 이미지 수 — 최신 회차에만 존재 */
  additionalImageCount?: number;
}

export interface FacilityInspectionOverview {
  /** 시설물 전체 등급(최신 점검 기준) — null이면 미분류 */
  overallGrade: DefectGradeLetter | null;
  totalRounds: number;
  cumulativeDefectCount: number;
  unresolvedDefectCount: number;
  history: FacilityInspectionHistoryItem[];
}

// 시설물 상세 하위 드릴다운 — 하자 정보 패널 + 회차 간 비교 (dev-04-02, #489). 위 dev-05-02 타입과는
// 별개 화면(하자 드릴다운/회차비교)의 로컬 타입 — 이름 접두 Facility로 겹치지만 다른 라우트/목적.
// openapi.yaml FacilityDetailResponse는 "계획안·미구현" 상태라 전부 MSW 목 전용이다.
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — dashboard의 DefectGrade와 별개로 로컬 정의.
export type FacilityDefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';

// 백엔드 DefectStatus(backend/.../defect/entity/DefectStatus.java: DETECTED/CONFIRMED/
// ACTION_PENDING/IN_PROGRESS/RESOLVED)와 이름을 그대로 정합시킨 조치 상태.
export type FacilityDefectStatus =
  | 'DETECTED'
  | 'CONFIRMED'
  | 'ACTION_PENDING'
  | 'IN_PROGRESS'
  | 'RESOLVED';

export interface FacilityDefectDetail {
  id: number;
  facilityId: number;
  facilityName: string;
  defectType: string;
  grade: FacilityDefectGrade;
  confidencePercent: number;
  widthMm: number;
  lengthM: number;
  foundCycle: number;
  /** YYYY-MM-DD */
  foundAt: string;
  location: string;
  assigneeName: string;
  status: FacilityDefectStatus;
  imageUrl: string;
}

export interface FacilityDefectActivityLogItem {
  id: number;
  message: string;
  /** 화면 표시용 축약 날짜(예: "6.22") — Figma 표기와 1:1 */
  occurredAtLabel: string;
}

export interface FacilityDefectAiExplanation {
  diagnosis: string;
  recommendedAction: string;
}

// 회차 간 비교 화면 전용 타입.
export interface InspectionCycleOption {
  cycle: number;
  /** YYYY-MM-DD */
  date: string;
}

export type ComparisonKpiKey = 'newDefects' | 'worsening' | 'resolved' | 'gradeEscalated';

export interface ComparisonKpi {
  key: ComparisonKpiKey;
  label: string;
  value: number;
  /** 증감(부호 포함) — 양수=악화/증가 방향, 음수=개선/감소 방향. 색상 규칙은 utils/formatComparisonChange.ts */
  changeValue: number;
}

export interface CrackTrendPoint {
  /** X축 라벨(예: "5회차") */
  cycleLabel: string;
  avgWidthMm: number;
}

export type DefectChangeType = 'worsened' | 'new' | 'unchanged' | 'resolved';

export interface DefectChangeRow {
  id: number;
  location: string;
  defectType: string;
  gradeBefore: FacilityDefectGrade | null;
  gradeAfter: FacilityDefectGrade | null;
  changeType: DefectChangeType;
  note: string;
}

export interface InspectionComparisonResult {
  facilityId: number;
  facilityName: string;
  beforeCycle: InspectionCycleOption;
  afterCycle: InspectionCycleOption;
  beforeImageUrl: string;
  afterImageUrl: string;
  kpis: ComparisonKpi[];
  crackTrend: CrackTrendPoint[];
  changes: DefectChangeRow[];
  /** 회차 선택 드롭다운에 노출할 전체 선택지 */
  availableCycles: InspectionCycleOption[];
}
