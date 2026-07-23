import type { DragEndEvent } from '@dnd-kit/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import { NEXT_STATUS } from '../components/DefectStatusStepper';
import type { Defect, DefectListFilters, DefectStatus } from '../types';
import { defectKeys, useDefects } from './useDefects';

// 조치 보드는 페이지네이션 없이 필터에 해당하는 전체 하자를 한 번에 컬럼별로 묶어 보여준다(#630,
// "보드 스코프는 전체 하자" 확정). Spring Data 기본 max-page-size(2000)보다 훨씬 작으면서도 실사용
// 규모를 넉넉히 덮는 값으로 고정 — 목록 탭의 DEFAULT_SIZE(10)와는 별도 상수다.
export const BOARD_PAGE_SIZE = 200;

// DefectStatusStepper.STEP_LABEL과 동일한 5단계 순서(handoff §구현요구사항 1).
export const BOARD_STATUSES: DefectStatus[] = [
  'DETECTED',
  'CONFIRMED',
  'ACTION_PENDING',
  'IN_PROGRESS',
  'RESOLVED',
];

export interface DefectBoardColumnData {
  status: DefectStatus;
  defects: Defect[];
}

export interface ReasonRequest {
  defect: Defect;
  targetStatus: DefectStatus;
}

type DropKind = 'noop' | 'forward' | 'reason-required';

// dnd-kit 이벤트 처리와 분리된 순수 판별 함수 — 실제 드래그 시뮬레이션 없이 유닛 테스트로 검증할 수
// 있게 분리했다. NEXT_STATUS[RESOLVED]는 null이라 RESOLVED에서의 모든 이동은 'reason-required'로
// 분류되고(백엔드가 어차피 409로 거부), 같은 컬럼에 놓으면 'noop'.
export function resolveDropKind(currentStatus: DefectStatus, targetStatus: DefectStatus): DropKind {
  if (currentStatus === targetStatus) {
    return 'noop';
  }
  return NEXT_STATUS[currentStatus] === targetStatus ? 'forward' : 'reason-required';
}

// 목록 탭과 필터(유형/등급/상태)는 공유하되 page/size는 보드 전용값으로 고정한다 — 목록 탭의
// useDefects(filters) 쿼리 캐시와 섞이지 않도록 정규화된 키를 만든다.
function toBoardFilters(filters: DefectListFilters): DefectListFilters {
  return {
    type: filters.type,
    grade: filters.grade,
    status: filters.status,
    page: 0,
    size: BOARD_PAGE_SIZE,
  };
}

interface DropMutationVariables {
  id: number;
  status: DefectStatus;
  reason?: string;
}

interface DropMutationContext {
  previous: PageResponse<Defect> | undefined;
}

// 하자 조치 보드(칸반) 상태 — 조회 + 드래그 드롭 시 정방향/역행 판별 + 낙관적 업데이트 + 사유 모달
// 오케스트레이션을 한 곳에 모은다(HAJA-349/#630). dnd-kit의 DragEndEvent만 받으면 되도록 설계해
// DefectActionBoard 컴포넌트는 UI 렌더링에만 집중한다.
export function useDefectActionBoard(filters: DefectListFilters) {
  const queryClient = useQueryClient();
  // 매 렌더 새 객체를 만들어도 무해하다 — TanStack Query는 queryKey를 구조적으로 비교하므로 참조
  // 동일성이 캐시 히트에 영향을 주지 않는다(다른 훅들과 동일 패턴, useDefects 참조).
  const boardFilters = toBoardFilters(filters);
  const listQueryKey = defectKeys.list(boardFilters);
  const { data, isLoading, isError, refetch } = useDefects(boardFilters);
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);
  const [dropError, setDropError] = useState<string | null>(null);

  const mutation = useMutation<Defect, ApiError, DropMutationVariables, DropMutationContext>({
    mutationFn: ({ id, status, reason }) => defectApi.updateStatus(id, status, reason).then((res) => res.data),
    onMutate: async (variables) => {
      await queryClient.cancelQueries({ queryKey: listQueryKey });
      const previous = queryClient.getQueryData<PageResponse<Defect>>(listQueryKey);
      queryClient.setQueryData<PageResponse<Defect>>(listQueryKey, (old) =>
        old
          ? {
              ...old,
              content: old.content.map((defect) =>
                defect.id === variables.id ? { ...defect, status: variables.status } : defect,
              ),
            }
          : old,
      );
      setDropError(null);
      return { previous };
    },
    onError: (error, _variables, context) => {
      if (context?.previous) {
        queryClient.setQueryData(listQueryKey, context.previous);
      }
      setDropError(error.message || '상태 변경에 실패했습니다.');
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: listQueryKey });
    },
  });

  const defects = useMemo(() => data?.content ?? [], [data]);

  const columns = useMemo<DefectBoardColumnData[]>(
    () =>
      BOARD_STATUSES.map((status) => ({
        status,
        defects: defects.filter((defect) => defect.status === status),
      })),
    [defects],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over) {
        return;
      }

      const defectId = Number(active.id);
      const targetStatus = over.id as DefectStatus;
      const defect = defects.find((item) => item.id === defectId);
      if (!defect) {
        return;
      }

      const kind = resolveDropKind(defect.status, targetStatus);
      if (kind === 'noop') {
        return;
      }

      if (kind === 'forward') {
        mutation.mutate({ id: defect.id, status: targetStatus });
        return;
      }

      setReasonRequest({ defect, targetStatus });
    },
    [defects, mutation],
  );

  const submitReason = useCallback(
    (reason: string) => {
      if (!reasonRequest) {
        return;
      }
      mutation.mutate({ id: reasonRequest.defect.id, status: reasonRequest.targetStatus, reason });
      setReasonRequest(null);
    },
    [reasonRequest, mutation],
  );

  const cancelReasonRequest = useCallback(() => setReasonRequest(null), []);
  const clearDropError = useCallback(() => setDropError(null), []);

  return {
    columns,
    isLoading,
    isError,
    refetch,
    handleDragEnd,
    reasonRequest,
    submitReason,
    cancelReasonRequest,
    dropError,
    clearDropError,
  };
}
