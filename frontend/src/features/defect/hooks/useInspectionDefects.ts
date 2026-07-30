import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';

// 점검 상세(카드형, HAJA-393/394 §화면 구조 ②) 데이터 소스 — GET /api/inspections/{id}/defects.
// id가 아직 없을 때(라우트 파라미터 파싱 전)는 요청을 보내지 않는다(enabled: false, useDefect.ts와 동일 패턴).
export const inspectionDefectsKeys = {
  byInspection: (inspectionId: number) => ['defect', 'by-inspection', inspectionId] as const,
};

export function useInspectionDefects(inspectionId: number | undefined) {
  return useQuery({
    queryKey: inspectionDefectsKeys.byInspection(inspectionId ?? -1),
    queryFn: () => defectApi.getByInspection(inspectionId as number).then((res) => res.data),
    enabled: inspectionId != null && !Number.isNaN(inspectionId),
  });
}
