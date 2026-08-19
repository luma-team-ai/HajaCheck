import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../../inspection/api/inspectionApi';
import type { InspectionResponse } from '../../inspection/api/inspectionApi.types';

// 점검 상세(카드형) 헤더의 점검 상태 배지(#1693) — GET /api/inspections/{id}. handoff 지시대로
// inspection feature의 inspectionApi.getInspection을 그대로 래핑한다(계약 변경 없음, 이 엔드포인트만
// 예외적으로 feature 간 직접 import — inspection feature가 이미 소유한 InspectionResponse 6종
// status를 그대로 쓰기 위함). 하자 목록 조회(useInspectionDefects)와 완전히 독립된 쿼리라, 이 훅의
// 로딩/에러가 하자 카드 렌더를 막지 않는다(InspectionDefectsPage — 배지만 미표시, 하자 목록은
// 별도로 정상 렌더). id가 아직 없을 때(라우트 파라미터 파싱 전)는 요청을 보내지 않는다
// (enabled: false, useInspectionDefects.ts와 동일 패턴).
export const inspectionKeys = {
  detail: (inspectionId: number) => ['defect', 'inspection', inspectionId] as const,
};

export function useInspection(inspectionId: number | undefined) {
  return useQuery<InspectionResponse>({
    queryKey: inspectionKeys.detail(inspectionId ?? -1),
    queryFn: () => inspectionApi.getInspection(inspectionId as number).then((res) => res.data),
    enabled: inspectionId != null && !Number.isNaN(inspectionId),
  });
}
