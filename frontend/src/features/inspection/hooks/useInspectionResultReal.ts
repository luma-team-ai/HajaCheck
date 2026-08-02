import { useQueries } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';
import { DEFECT_TYPE_CODE_LABELS } from '../api/inspectionApi.types';
import type { DefectBoundingBox, Defect, InspectionResult, InspectionMedia } from '../types';

// 뷰어는 상세 이미지(detailUrl, 그리드용 썸네일보다 큰 해상도)를 우선 사용 — 크랙 폭처럼 눈으로
// 판별해야 하는 상세검수 화면에 400px 썸네일은 너무 작다(#788). export로 분리해 단위 테스트 가능하게.
export function resolveDefectImageUrl(
  imageUrl: string | null | undefined,
  detailUrl: string | null | undefined,
): string | null {
  return detailUrl ?? imageUrl ?? null;
}

/**
 * 실제 API에서 점검 결과를 조회한다. 네 개의 독립적인 쿼리를 병렬 실행하고 결합한다:
 * 1. GET /api/inspections/{id} — 점검 회차 정보
 * 2. GET /api/inspections/{id}/defects — 하자 목록
 * 3. GET /api/inspections/{id}/media — 전체 미디어 목록 (하자 유무 무관, #804)
 * 4. GET /api/facilities/{facilityId} — 시설물 정보
 *
 * ponytail: 네 쿼리의 로딩/에러 상태를 단순화했다(모두 로드될 때까지 isLoading,
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
      {
        queryKey: ['inspection', inspectionId, 'media'],
        queryFn: () => inspectionApi.getMedia(inspectionId).then((res) => res.data),
        enabled: isValidId,
      },
    ],
  });

  const [inspectionQuery, defectsQuery, mediaQuery] = results;
  const inspection = inspectionQuery.data;
  const defectsData = defectsQuery.data;
  const mediaData = mediaQuery.data;

  // ponytail: useQueries는 튜플 타입 추론이 고정 크기를 요구하므로 조건부 배열 대신
  // 고정 크기 배열 + enabled 플래그로 제어 (inspection이 없으면 쿼리 스킵)
  const [facilityQuery] = useQueries({
    queries: [
      {
        queryKey: ['facility', inspection?.facilityId ?? 0],
        queryFn: () =>
          inspection
            ? inspectionApi.getFacilityDetail(inspection.facilityId).then((res) => res.data)
            : Promise.resolve(null),
        enabled: !!(inspection && isValidId),
      },
    ],
  });

  const isLoading = inspectionQuery.isLoading || defectsQuery.isLoading || mediaQuery.isLoading || facilityQuery.isLoading;
  const isError = inspectionQuery.isError || defectsQuery.isError || mediaQuery.isError || facilityQuery.isError;

  // defectCode: 데이터 없거나 시뮬레이션용. 백엔드에서 제공하지 않으면 inspection.id로 임시 구성.
  const defectCode = inspection ? `DEF-${String(inspection.id).padStart(4, '0')}` : '';

  // defects 변환: bbox, measurements, mediaId/imageUrl/areaRatio 포함
  const transformedDefects: Defect[] = (defectsData || []).map((d) => {
    // 백엔드는 type을 영문 코드로 내려준다(openapi.yaml DefectTypeCode) — 화면 표시·유형별
    // 분기(균열=선형/그 외=면적형)는 한글 라벨 기준이라 여기서 한 번만 번역한다(#881).
    const typeLabel = DEFECT_TYPE_CODE_LABELS[d.type];
    return {
      id: d.id,
      type: typeLabel,
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
      areaRatio: d.areaRatio ?? undefined, // 박리박락·철근노출 전용(#804)
      // ponytail: summary는 백엔드에서 제공하지 않으므로 기본값. AI explain으로 채울 수 있음(후속).
      summary: `${typeLabel} 하자 — 신뢰도 ${Math.round(d.confidence * 100)}%`,
      mediaId: d.mediaId ?? null,
      imageUrl: resolveDefectImageUrl(d.imageUrl, d.detailUrl),
      thumbnailUrl: d.imageUrl ?? null,
    };
  });

  const reviewedCount = (defectsData || []).filter((d) => d.isReviewed).length;
  const totalCount = transformedDefects.length;

  // 모든 데이터 준비 완료
  // media를 detailUrl(고해상도) 또는 thumbnailUrl(폴백)로 변환 (#804)
  // DefectOverlay의 img 태그에서 detail 로드 실패 시 thumbnail로 폴백 가능하도록 둘 다 전달(#796)
  const transformedMedia: InspectionMedia[] = (mediaData || []).map((m) => ({
    id: m.id,
    imageUrl: m.detailUrl ?? m.thumbnailUrl ?? '',
    thumbnailUrl: m.thumbnailUrl,
  }));

  const data: InspectionResult | null =
    inspection && isValidId && facilityQuery.data
      ? {
          inspectionId: inspection.id,
          roundNo: inspection.roundNo, // 보고서 생성 진입점의 "N회차" 표기용(#876)
          media: transformedMedia, // 전체 미디어 목록(하자 유무 무관, #804)
          defects: transformedDefects,
          defectCode,
          facilityName: facilityQuery.data.name,
          facilityType: facilityQuery.data.type,
          status: inspection.status,
          reviewedCount,
          totalCount,
        }
      : null;

  return {
    data,
    isLoading,
    isError,
    // 네 쿼리를 모두 기다리는 Promise를 반환한다 — 예전엔 각 refetch()의 Promise를 버리고
    // undefined를 반환해서, 호출부의 `await refetch()`가 즉시 통과했다(#1395). 검수·오탐 삭제
    // 직후 목록이 갱신되기 전에 모달이 닫히고 버튼이 다시 활성화돼, 같은 하자를 한 번 더 누르면
    // 서버가 INVALID_STATE_TRANSITION(409)을 던지고 그 원문이 사용자에게 노출됐다.
    refetch: () =>
      Promise.all([
        inspectionQuery.refetch(),
        defectsQuery.refetch(),
        mediaQuery.refetch(),
        facilityQuery.refetch(),
      ]).then(() => undefined),
  };
}
