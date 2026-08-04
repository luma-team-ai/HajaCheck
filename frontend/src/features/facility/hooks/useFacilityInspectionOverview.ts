import { useQuery } from '@tanstack/react-query';
import { facilityApi } from '../api/facilityApi';
import type { FacilityInspectionOverview, FacilityInspectionOverviewApiResponse } from '../types';

// 썸네일 미리보기 고정 2장(FacilityInspectionHistoryItem.tsx) 외 나머지 이미지 수 — 최신 회차에만 표시.
// 백엔드는 imageCount(총 장수)만 내려주고, "미리보기 몇 장을 뺄지"는 순수 UI 레이아웃 상수라 프론트에서 계산한다.
const THUMBNAIL_PREVIEW_COUNT = 2;

function toOverview(response: FacilityInspectionOverviewApiResponse): FacilityInspectionOverview {
  return {
    overallGrade: response.overallGrade,
    totalRounds: response.totalRounds,
    cumulativeDefectCount: response.cumulativeDefectCount,
    unresolvedDefectCount: response.unresolvedDefectCount,
    history: response.history.map((item, index) => ({
      id: item.id,
      roundNo: item.roundNo,
      inspectionDate: item.inspectionDate,
      inspectorName: item.inspectorName,
      status: item.status,
      imageCount: item.imageCount,
      defectGradeBreakdown: item.defectGradeBreakdown,
      // changeNote/additionalImageCount/thumbnailUrls는 최신 회차(index 0)에만 존재
      // (#1359/HAJA-616 계약 그대로 유지) — 백엔드도 최신 회차에만 채워 보내지만, 이 매퍼가
      // 이미 다른 필드에 쓰는 것과 같은 index===0 방어를 여기도 맞춰 둔다(code-reviewer 지적, #1549).
      changeNote: index === 0 && item.changeNote ? item.changeNote : undefined,
      thumbnailUrls: index === 0 ? item.thumbnailUrls : [],
      additionalImageCount:
        index === 0 && item.imageCount > THUMBNAIL_PREVIEW_COUNT
          ? item.imageCount - THUMBNAIL_PREVIEW_COUNT
          : undefined,
    })),
  };
}

// 시설물 상세 "점검 이력" 탭(#1359/HAJA-616) — backend GET /api/facilities/{id}/inspections 실연동.
export function useFacilityInspectionOverview(facilityId: number) {
  return useQuery({
    queryKey: ['facility', facilityId, 'inspection-overview'] as const,
    queryFn: () => facilityApi.getInspectionOverview(facilityId).then((res) => toOverview(res.data)),
  });
}
