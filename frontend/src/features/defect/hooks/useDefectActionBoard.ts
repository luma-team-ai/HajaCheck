import type { DragEndEvent } from '@dnd-kit/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import { NEXT_STATUS, REASON_REQUIRED_TARGETS, STEP_LABEL } from '../constants/defectStatusWorkflow';
import type { Defect, DefectListFilters, DefectStatus } from '../types';
import { defectKeys, useDefects } from './useDefects';

// 조치 보드는 페이지네이션 없이 필터에 해당하는 전체 하자를 한 번에 컬럼별로 묶어 보여준다(#630,
// "보드 스코프는 전체 하자" 확정). Spring Data 기본 max-page-size(2000)보다 훨씬 작으면서도 실사용
// 규모를 넉넉히 덮는 값으로 고정 — 목록 탭의 DEFAULT_SIZE(10)와는 별도 상수다.
export const BOARD_PAGE_SIZE = 200;

// defectStatusWorkflow.STEP_LABEL과 동일한 4단계 순서(handoff §구현요구사항 1).
export const BOARD_STATUSES: DefectStatus[] = [
  'DETECTED',
  'CONFIRMED',
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

type DropKind = 'noop' | 'forward' | 'reason-required' | 'invalid';

// dnd-kit 이벤트 처리와 분리된 순수 판별 함수 — 실제 드래그 시뮬레이션 없이 유닛 테스트로 검증할 수
// 있게 분리했다. 같은 컬럼에 놓으면 'noop'.
//
// (#1642) 과거엔 정방향(NEXT_STATUS)이 아니면 무조건 'reason-required'로 분류해, 보드 드래그가
// defectStatusWorkflow.ts에 정의되지 않은 전이(예: CONFIRMED→RESOLVED 직행)도 사유 모달만 거쳐
// 통과시켰다 — 조치결과등록(PATCH /defects/{id}/action)을 건너뛰어 조치 사진/내용 없이
// defect_action_logs가 비는 버그의 두 번째 진입로였다(첫 번째는 DefectActionForm "진행상태" select,
// #1642에서 REASON_REQUIRED_TARGETS.CONFIRMED에서 RESOLVED 제거로 이미 차단). 이제 보드도 같은
// 단일 진실 소스(NEXT_STATUS + REASON_REQUIRED_TARGETS)만 유효한 전이로 인정하고, 그 외는
// 'invalid'로 분류해 드롭 자체를 거부한다(모달을 띄우지 않음 — 백엔드 호출 없이 프런트에서 막는다).
export function resolveDropKind(currentStatus: DefectStatus, targetStatus: DefectStatus): DropKind {
  if (currentStatus === targetStatus) {
    return 'noop';
  }
  if (NEXT_STATUS[currentStatus] === targetStatus) {
    return 'forward';
  }
  const reasonTargets = REASON_REQUIRED_TARGETS[currentStatus] ?? [];
  return reasonTargets.includes(targetStatus) ? 'reason-required' : 'invalid';
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
      // 스냅샷 복구만으로는 서버와 다시 어긋날 수 있는 경우(예: 같은 카드가 진행 중인 뮤테이션 위에
      // 재드래그돼 onMutate가 이미 낙관적으로 바뀐 상태를 "원본"으로 스냅샷한 경우)를 방어하기 위해
      // 항상 무효화해서 최종적으로는 서버 상태로 재동기화되게 한다(code-reviewer 지적, HAJA-349/#630).
      queryClient.invalidateQueries({ queryKey: listQueryKey });
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

      // 이전 드롭이 아직 처리 중이면 새 드롭을 무시한다 — 단일 mutation을 재진입하면 두 번째
      // onMutate가 첫 번째 낙관적 업데이트 "이후" 상태를 원본으로 스냅샷해 롤백이 꼬인다
      // (code-reviewer 지적, HAJA-349/#630). invalidateQueries(onError)와 함께 이중 방어.
      if (mutation.isPending) {
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

      // (#1642) 정의되지 않은 전이 — API를 호출하지 않고 기존 dropError 배너(role="alert" +
      // 닫기 버튼)를 재사용해 1줄 안내만 띄운다. 별도 토스트 시스템이 없는 컨벤션(모달 안내와 동일
      // 패턴 — DefectStatusReasonModal.tsx:23-25 참고)에 맞춘 최소 구현.
      if (kind === 'invalid') {
        setDropError(
          `${STEP_LABEL[defect.status]}에서 ${STEP_LABEL[targetStatus]}(으)로는 바로 이동할 수 없습니다.`,
        );
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
      if (!reasonRequest || mutation.isPending) {
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
