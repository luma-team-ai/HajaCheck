import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';
import type { DefectActionLogPhase } from '../types';

export const defectActionLogsKeys = {
  byPhase: (defectId: number, phase: DefectActionLogPhase) =>
    ['defect', 'action-logs', defectId, phase] as const,
};

// 하자 상세 모달 사진 탭(#1193/HAJA-569 백엔드, #1211/HAJA-574 프론트) — phase(IN_PROGRESS="조치
// 사진"/RESOLVED="조치 완료 사진")별 조치 등록 이력을 조회한다. useDefectRevisions와 동일하게 id가
// 아직 없을 때는 요청을 보내지 않는다.
export function useDefectActionLogs(defectId: number | undefined, phase: DefectActionLogPhase) {
  return useQuery({
    queryKey: defectActionLogsKeys.byPhase(defectId ?? -1, phase),
    queryFn: () => defectApi.getActionLogs(defectId as number, phase).then((res) => res.data),
    enabled: defectId != null && !Number.isNaN(defectId),
  });
}
