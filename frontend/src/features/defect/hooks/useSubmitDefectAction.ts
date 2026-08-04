import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import { inspectionDefectsKeys } from './useInspectionDefects';
import { defectKeys } from './useDefects';
import { defectActionLogsKeys } from './useDefectActionLogs';
import { defectRevisionsKeys } from './useDefectRevisions';
import type { Defect, DefectActionSubmitRequest } from '../types';

// 하자 상세 모달 "조치 완료 등록" 제출(HAJA-394/#726) — PATCH /api/defects/{id}/action(확정,
// contract.md §"조치 결과 등록"). 성공 시 상세 캐시를 갱신하고, 같은 점검의
// 카드 그리드(useInspectionDefects)도 최신 상태를 반영하도록 무효화한다. IN_PROGRESS 유지 재제출로
// 조치 사진이 여러 번 쌓일 수 있으므로(#1193/HAJA-569) 두 phase의 action-logs 캐시도 함께
// 무효화한다 — 제출 직후 상세 모달의 사진 탭 이력이 갱신되도록 한다.
export function useSubmitDefectAction(defectId: number | undefined, inspectionId: number | undefined) {
  const queryClient = useQueryClient();

  const mutation = useMutation<Defect, ApiError, DefectActionSubmitRequest>({
    mutationFn: (body) => defectApi.submitAction(defectId as number, body).then((res) => res.data),
    onSuccess: (updated) => {
      if (defectId != null) {
        queryClient.setQueryData(defectKeys.detail(defectId), updated);
        queryClient.invalidateQueries({ queryKey: defectActionLogsKeys.byPhase(defectId, 'IN_PROGRESS') });
        queryClient.invalidateQueries({ queryKey: defectActionLogsKeys.byPhase(defectId, 'RESOLVED') });
        // 상태가 바뀐 제출은 defect_revisions에도 기록되므로(#1193/HAJA-569), 활동 기록 패널이
        // 재조회 없이도 즉시 최신 내역을 보여주도록 함께 무효화한다(#1553).
        queryClient.invalidateQueries({ queryKey: defectRevisionsKeys.byDefect(defectId) });
      }
      if (inspectionId != null) {
        queryClient.invalidateQueries({ queryKey: inspectionDefectsKeys.byInspection(inspectionId) });
      }
    },
  });

  return {
    submitAction: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
