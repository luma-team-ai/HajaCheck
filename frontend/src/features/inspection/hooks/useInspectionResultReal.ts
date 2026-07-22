import { useQueries } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';
import type { DefectBoundingBox, Defect, InspectionResult } from '../types';

/**
 * 실제 API에서 점검 결과를 조회한다. 세 개의 독립적인 쿼리를 병렬 실행하고 결합한다:
 * 1. GET /api/inspections/{id} — 점검 회차 정보
 * 2. GET /api/inspections/{id}/defects — 하자 목록
 * 3. GET /api/facilities/{facilityId} — 시설물 정보
 *
 * ponytail: 세 쿼리의 로딩/에러 상태를 단순화했다(모두 로드될 때까지 isLoading,
 * 하나라도 실패하면 isError). 개별 상태 분리가 필요하면 useQueries 결과를 직접 사용할 것.
 */
export function useInspectionResultReal(inspectionId: number) {
  const isValidId = Number.isInteger(inspectionId) && inspectionId > 0;

  const results = useQueries({
    queries: [
      {
        queryKey: ['inspection', inspectionId],
        queryFn: () => inspectionApi.getInspection(inspectionId).then((res) => res.data),
        enabled: isValidId,
      },
      {
        queryKey: ['inspection', inspectionId, 'defects'],
        queryFn: () => inspectionApi.getDefects(inspectionId).then((res) => res.data),
        enabled: isValidId,
      },
    ],
  });

  const [inspectionQuery, defectsQuery] = results;
  const inspection = inspectionQuery.data;
  const defectsData = defectsQuery.data;

  // 시설물 조회는 inspection.facilityId를 알아야만 실행 가능
  const facilityQuery = useQueries({
    queries:
      inspection && isValidId
        ? [
            {
              queryKey: ['facility', inspection.facilityId],
              queryFn: () =>
                inspectionApi.getFacilityDetail(inspection.facilityId).then((res) => res.data),
              enabled: true,
            },
          ]
        : [],
  })[0];

  const isLoading = inspectionQuery.isLoading || defectsQuery.isLoading || facilityQuery?.isLoading;
  const isError = inspectionQuery.isError || defectsQuery.isError || facilityQuery?.isError;

  // defectCode: 데이터 없거나 시뮬레이션용. 백엔드에서 제공하지 않으면 inspection.id로 임시 구성.
  const defectCode = inspection ? `DEF-${String(inspection.id).padStart(4, '0')}` : '';

  // defects 변환: bbox와 measurements 포함
  const transformedDefects: Defect[] = (defectsData || []).map((d) => ({
    id: d.id,
    type: d.type,
    grade: d.grade,
    status: d.status,
    confidence: d.confidence,
    bbox: {
      x: d.bboxX,
      y: d.bboxY,
      width: d.bboxW,
      height: d.bboxH,
    } as DefectBoundingBox,
    widthMm: d.crackWidthMm,
    lengthMm: d.crackLengthMm,
    // ponytail: summary는 백엔드에서 제공하지 않으므로 기본값. AI explain으로 채울 수 있음(후속).
    summary: `${d.type} 하자 — 신뢰도 ${Math.round(d.confidence * 100)}%`,
  }));

  const reviewedCount = transformedDefects.filter((d) => d.status === '검수확정').length;
  const totalCount = transformedDefects.length;

  // 모든 데이터 준비 완료
  const data: InspectionResult | null =
    inspection && isValidId && facilityQuery?.data
      ? {
          inspectionId: inspection.id,
          // ponytail: 백엔드에서 media list 엔드포인트 미제공. 실제 이미지 URL은 추가 구현 필요.
          // 지금은 더미 객체 반환 — DefectOverlay는 이 필드를 사용하지 않음(bbox 좌표만 사용).
          media: {
            id: 0,
            imageUrl: '',
            width: 1,
            height: 1,
          },
          defects: transformedDefects,
          defectCode,
          facilityName: facilityQuery.data.name,
          status: inspection.status,
          reviewedCount,
          totalCount,
        }
      : null;

  return {
    data,
    isLoading,
    isError,
    refetch: () => {
      inspectionQuery.refetch();
      defectsQuery.refetch();
      if (facilityQuery) facilityQuery.refetch();
    },
  };
}
