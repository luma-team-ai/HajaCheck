// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// 백엔드 /api/facilities 연동 (dev-04-04)
import { api } from '../../../shared/api/axios';
import type { FacilityLocation } from '../types';

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
        // 백엔드 FacilityResponse에 등급/하자건수 필드가 아직 없다(등급 산정 API 미구현, #661).
        // 실데이터가 없는 값을 임의 공식으로 지어내 실제 안전등급처럼 노출하지 않도록 null로
        // 내려보내고, UI(마커/팝업/목록)는 null을 "등급 미정"으로 폴백 처리한다.
        highestGrade: null,
        warningCount: null,
        cautionCount: null,
        thumbnailUrl: null,
      }));
  },
};
