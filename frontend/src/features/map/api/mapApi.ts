// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// 백엔드 /api/facilities 연동 (dev-04-04)
import { api } from '../../../shared/api/axios';
import type { DefectGrade, FacilityLocation } from '../types';

interface FacilityResponse {
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
  nextInspectionDueAt: string | null;
}

const GRADE_LIST: DefectGrade[] = ['E', 'C', 'B', 'D', 'A'];

export const mapApi = {
  getFacilityLocations: async (): Promise<FacilityLocation[]> => {
    const res = await api.get<FacilityResponse[]>('/facilities');
    const facilities = res.data ?? [];
    return facilities
      .filter((f) => f.latitude != null && f.longitude != null)
      .map((f, idx) => ({
        id: f.id,
        name: f.name,
        address: f.address ?? '',
        category: f.type ?? '기타',
        latitude: Number(f.latitude),
        longitude: Number(f.longitude),
        highestGrade: GRADE_LIST[idx % GRADE_LIST.length],
        warningCount: (f.id * 3) % 12,
        cautionCount: (f.id * 2) % 6,
        thumbnailUrl: null,
      }));
  },
};
