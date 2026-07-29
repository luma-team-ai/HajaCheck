// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// 백엔드 /api/facilities 연동 (dev-04-04)
import { api } from '../../../shared/api/axios';
import type { FacilityLocation } from '../types';

interface FacilityResponse {
  id: number;
  name: string;
  type: string;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  builtYear: number | null;
  scale: string | null;
  inspectionCycleMonths: number | null;
  nextInspectionDueAt: string | null;
  highestGrade: FacilityLocation['highestGrade'];
  warningCount: number | null;
  cautionCount: number | null;
  thumbnailUrl: string | null;
}

export const mapApi = {
  getFacilityLocations: async (): Promise<FacilityLocation[]> => {
    const res = await api.get<FacilityResponse[]>('/facilities');
    const facilities = res.data ?? [];
    return facilities
      .filter((f) => f.latitude != null && f.longitude != null)
      .map((f) => ({
        id: f.id,
        name: f.name,
        address: f.address ?? '',
        category: f.type ?? '기타',
        latitude: Number(f.latitude),
        longitude: Number(f.longitude),
        highestGrade: f.highestGrade ?? null,
        warningCount: f.warningCount ?? 0,
        cautionCount: f.cautionCount ?? 0,
        thumbnailUrl: f.thumbnailUrl ?? null,
      }));
  },
};
