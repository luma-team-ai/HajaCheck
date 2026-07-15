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
  nextInspectionDueAt: string | null; // ISO date — 서버 계산값(response-only)
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
}
