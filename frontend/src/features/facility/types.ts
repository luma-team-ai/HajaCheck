// 시설물 목록/등록 — dev-04-01(Figma), FR-003 — backend PR #176(dev 머지) 계약과 1:1
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — 대시보드 타입과 별개로 로컬 정의

export interface Facility {
  id: number;
  ownerId: number;
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
