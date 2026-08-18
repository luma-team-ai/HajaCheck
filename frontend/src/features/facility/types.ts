// 시설물 목록/등록 — dev-04-01(Figma), FR-003 — backend PR #176(dev 머지) 계약과 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — 대시보드 타입과 별개로 로컬 정의

// 시설물 등록 필드 확장(#628/HAJA-347) — backend FacilityInitialGrade(A~E) 그대로 정합.
// ⚠️ 대시보드/하자 정보 패널이 쓰는 FacilityDefectGrade(점검 이력 기반 계산값)와는 별개의 독립
// 개념이다(backend entity 주석과 동일) — 라벨 집합이 같을 뿐 서로 혼용하지 않는다.
export type FacilityInitialGrade = 'A' | 'B' | 'C' | 'D' | 'E';

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
  // #628(HAJA-347) 등록 필드 확장 — 전부 선택 입력. 대표 사진은 별도 엔드포인트
  // (POST/GET /api/facilities/{id}/media, #1017/#632)로 시설물 생성 후 조회/업로드한다 — Facility
  // 자체 응답에는 사진 필드가 없다(#652).
  initialGrade: FacilityInitialGrade | null;
  assigneeUserId: number | null;
  memo: string | null;
  // 시설물 상세→하자 오버레이 직행(HAJA-434 갭1) — 대표(최신) 하자 id, 하자가 없으면 null.
  latestDefectId: number | null;
  // 시설물 카드 대표 사진(HAJA-367/#670) — /api/media/{id}/thumbnail 경로, 사진이 없으면 null.
  thumbnailUrl: string | null;
  // 시설물 카드 "최근 점검 MM.dd"(HAJA-514/#1074) — ISO date(YYYY-MM-DD), 점검 이력이 없으면 null.
  lastInspectedAt: string | null;
  // 지도/목록 실데이터 집계 — 백엔드가 기존 inspections/defects에서 계산한다.
  highestGrade?: FacilityInitialGrade | null;
  warningCount?: number | null;
  cautionCount?: number | null;
  // 시설물 카드 하자건수 배지(HAJA-515/#1075) — 비삭제 하자 총건수, 하자가 없으면 0(null 아님).
  defectCount: number;
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
  // #628(HAJA-347) — 전부 선택 입력. assigneeUserId는 값이 있을 때만 서버가 배정 가능 검사자인지
  // 검증한다(AuthService.validateAssignableInspector와 동일 패턴).
  initialGrade?: FacilityInitialGrade | null;
  assigneeUserId?: number | null;
  memo?: string | null;
}

// 시설물 대표 사진(#652) — POST/GET /api/facilities/{id}/media, backend MediaResponse와 1:1.
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — inspection/types.ts의 Media와 필드 셋이
// 같지만(같은 MediaResponse를 공유) 로컬로 복제한다. facility 사진은 점검 회차에 속하지 않으므로
// inspectionId는 항상 null로 내려온다(응답 자체에 facilityId 필드는 없음 — 호출한 엔드포인트로만 구분).
export interface FacilityPhoto {
  id: number;
  inspectionId: number | null;
  fileType: 'IMAGE' | 'VIDEO';
  thumbnailUrl: string;
  detailUrl: string;
  mimeType: string;
  capturedAt: string | null;
  gpsLat: number | null;
  gpsLng: number | null;
  createdAt: string;
}

// 담당자 select 옵션 — 배정 가능한 사용자 목록. 실 API 없음(2026-07-23 조사, auth 쪽에
// GET /api/users/me만 존재) → 이번 범위는 MSW 목 전용(facilityAssigneeApi.handlers.ts).
// 실 연동은 후속 이슈로 분리(inspection의 assignedInspectorId도 현재 숫자 ID 직접입력이라 동일 공백).
export interface FacilityAssignableUser {
  id: number;
  name: string;
}

// 점검 주기 설정(dev-04-03, FR-019) — POST /api/facilities/{id}/schedule 실 계약
export interface SetFacilityScheduleRequest {
  inspectionCycleMonths: number;
}

export interface SetFacilityScheduleResponse {
  nextInspectionDueAt: string | null;
}

// 점검 알림 설정(GitHub #540 ③) — GET/PUT /api/facilities/{id}/notification-settings,
// backend InspectionNotificationSettingResponse/Request와 1:1. 설정을 저장한 적 없는 시설물도
// 항상 유효한 값(서버 컬럼 기본값과 동일: 사전알림 사용/7일전/경과알림 사용, HAJA-498/V21)을 반환한다.
export interface InspectionNotificationSettings {
  notifyBeforeEnabled: boolean;
  notifyBeforeDays: number;
  warnOnOverdueEnabled: boolean;
}

export type SetInspectionNotificationSettingsRequest = InspectionNotificationSettings;

// 전체 시설물 점검 주기 현황(#1136 실연동) — GET /api/facilities/status, backend
// FacilityStatusResponse와 1:1. inspectionType은 Facility 자체가 아니라 가장 최근 점검 회차의
// 값을 근사 재사용한다(팀 결정 — DB 마이그레이션 없이 진행, option a). 점검 이력이 없으면 null.
export interface FacilityStatusRow {
  facilityId: number;
  facilityName: string;
  initialGrade: FacilityInitialGrade | null;
  nextInspectionDueAt: string | null;
  dDay: number | null;
  assigneeUserId: number | null;
  assigneeName: string | null;
  lastInspectedAt: string | null;
  inspectionCycleMonths: number | null;
  inspectionType: 'REGULAR' | 'DETAILED' | 'EMERGENCY' | null;
}

// 전체 시설물 점검 주기 현황 — 우측 테이블/좌측 카드 전용 화면 타입(FacilityStatusRow를
// useInspectionCycleStatusRows가 이 형태로 매핑한다 — mapFacilityStatusRow 참고).
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
  /** 미리보기 썸네일 URL(최대 2장) — 최신 회차에만 존재(#1549) */
  thumbnailUrls?: string[];
}

export interface FacilityInspectionOverview {
  /** 시설물 전체 등급(최신 점검 기준) — null이면 미분류 */
  overallGrade: DefectGradeLetter | null;
  totalRounds: number;
  cumulativeDefectCount: number;
  unresolvedDefectCount: number;
  history: FacilityInspectionHistoryItem[];
}

// 실 API 원본 응답(#1359/HAJA-616, backend FacilityInspectionOverviewResponse와 1:1) — additionalImageCount는
// 백엔드에 없다(썸네일 미리보기 고정 2장 제외분은 순수 프론트 표시 계산, useFacilityInspectionOverview 참고).
// thumbnailUrls는 최신 회차만 채워지고 나머지는 빈 배열(#1549, changeNote와 동일 관례).
export interface FacilityInspectionOverviewApiResponse {
  overallGrade: DefectGradeLetter | null;
  totalRounds: number;
  cumulativeDefectCount: number;
  unresolvedDefectCount: number;
  history: {
    id: number;
    roundNo: number;
    inspectionDate: string;
    inspectorName: string;
    status: '검수완료' | '완료' | '진행중';
    imageCount: number;
    defectGradeBreakdown: { grade: DefectGradeLetter; count: number }[];
    changeNote: string | null;
    thumbnailUrls: string[];
  }[];
}

// 시설물 상세 하위 드릴다운 — 하자 정보 패널 + 회차 간 비교 (dev-04-02, #489). 위 dev-05-02 타입과는
// 별개 화면(하자 드릴다운/회차비교)의 로컬 타입 — 이름 접두 Facility로 겹치지만 다른 라우트/목적.
// openapi.yaml FacilityDetailResponse는 "계획안·미구현" 상태라 전부 MSW 목 전용이다.
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — dashboard의 DefectGrade와 별개로 로컬 정의.
export type FacilityDefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';

// 백엔드 DefectStatus(backend/.../defect/entity/DefectStatus.java:
// DETECTED/CONFIRMED/IN_PROGRESS/RESOLVED)와 이름을 그대로 정합시킨 조치 상태.
export type FacilityDefectStatus =
  | 'DETECTED'
  | 'CONFIRMED'
  | 'IN_PROGRESS'
  | 'RESOLVED';

export interface FacilityDefectDetail {
  id: number;
  inspectionId: number;
  facilityId: number;
  facilityName: string;
  defectType: string;
  /** DDL상 nullable(defect_grade_type) — 미분류면 null(FacilityGradeBadge가 '-' 배지로 처리) */
  grade: FacilityDefectGrade | null;
  confidencePercent: number;
  /** 균열이 아닌 유형은 null(backend DefectResponse.crackWidthMm) */
  widthMm: number | null;
  /** 균열이 아닌 유형은 null(backend DefectResponse.crackLengthMm ÷ 1000, mm→m 변환) */
  lengthM: number | null;
  /** 박리박락·철근노출만 값이 있을 수 있음(backend DefectResponse.areaMm2, #1658/#1669) —
   * 카드 기준물 미검출이거나 균열 타입이면 null. widthMm/lengthM과 독립적으로 null 체크(#1588 교훈). */
  areaMm2: number | null;
  foundCycle: number;
  /** YYYY-MM-DD */
  foundAt: string;
  /** 검수자가 사후 편집하기 전에는 null(#970 갭3) */
  location: string | null;
  /** 시설물 담당자(Facility.assigneeUserId) 미배정이면 null(#970 갭3) */
  assigneeName: string | null;
  status: FacilityDefectStatus;
  /** mediaId 없으면 null(HAJA-314) */
  imageUrl: string | null;
  // #1369 — 오버레이 탭 마킹이 고정 placeholder라 항상 같은 위치에 표시되던 결함 수정. 0~1 정규화
  // 좌표(backend DefectResponse.bboxX/Y/W/H) — 넷 중 하나라도 null이면 마킹을 표시하지 않는다
  // (defect 기능의 DefectImageViewer.tsx와 동일한 hasBbox 가드 패턴).
  bboxX: number | null;
  bboxY: number | null;
  bboxW: number | null;
  bboxH: number | null;
}

// GET/PATCH /api/defects/{id}(*) 실 백엔드 DefectResponse(backend/.../defect/dto/DefectResponse.java)의
// 얇은 로컬 사본 — 하자 정보 패널이 실제로 쓰는 필드만 옮겨온다(feature 간 직접 import 금지,
// React_코드_컨벤션.md §1). FacilityDefectDetail(화면 소비용, facilityDefectApi가 매핑)과는 별개의
// 원본(raw) 계약 타입이다.
export interface FacilityDefectDetailResponse {
  id: number;
  inspectionId: number;
  facilityId: number;
  facilityName: string;
  location: string | null;
  assigneeName: string | null;
  foundCycle: number;
  typeLabel: string;
  grade: FacilityDefectGrade | null;
  status: FacilityDefectStatus;
  confidence: number;
  crackWidthMm: number | null;
  crackLengthMm: number | null;
  // 실측 면적(mm², #1658/#1669) — backend DefectResponse.areaMm2와 1:1.
  areaMm2: number | null;
  imageUrl: string | null;
  // #1369 — 0~1 정규화 좌표(backend DefectResponse.bboxX/Y/W/H), nullable.
  bboxX: number | null;
  bboxY: number | null;
  bboxW: number | null;
  bboxH: number | null;
  /** ISO 8601 (LocalDateTime) — 화면 표시는 YYYY-MM-DD로 절삭 */
  createdAt: string;
}

// PR머신 P1 — POST /api/ai/defect-explain 실 응답은 {cause,risk,action}이다(ai-server
// tests/test_defect_explain.py:60,76, defect 기능의 DefectExplain과 동일 계약). 이전에 여기 있던
// {diagnosis, recommendedAction}은 존재하지 않는 필드라 prod에서 항상 undefined로 렌더돼
// #1350이 고치려던 "AI 설명 빈 화면"이 응답 필드명 불일치로 재발했다.
export interface FacilityDefectAiExplanation {
  cause: string;
  risk: string;
  action: string;
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

// recurring(재발생, HAJA-532/#1119) — 이전 회차 RESOLVED였던 하자가 이후 회차에 previousDefectId로
// 재연결된 경우. 초기(#1112)엔 대응 타입이 없어 worsened로 근사 매핑했었으나 이제 정확히 구분한다.
export type DefectChangeType = 'worsened' | 'new' | 'unchanged' | 'resolved' | 'recurring';

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
  // 실 백엔드(GET /api/facilities/{id}/compare, HAJA-531/#1112)는 실 데이터 근거가 없어 이 두 필드를
  // 응답에서 아예 생략한다(2026-07-28 사용자 결정) — 타입을 그 실제 부재와 맞춘다. 채우는 작업은 #1346.
  // (같은 이유로 생략됐던 crackTrend는 실데이터 확보 경로 자체가 없어 화면에서 제거했다 — #1347.)
  beforeImageUrl: string | null;
  afterImageUrl: string | null;
  kpis: ComparisonKpi[];
  changes: DefectChangeRow[];
  /** 회차 선택 드롭다운에 노출할 전체 선택지 */
  availableCycles: InspectionCycleOption[];
}
