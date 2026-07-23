import { closestCenter, DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { useDefectActionBoard } from '../hooks/useDefectActionBoard';
import type { DefectListFilters } from '../types';
import { DefectBoardColumn } from './DefectBoardColumn';
import { DefectStatusReasonModal } from './DefectStatusReasonModal';
import { STEP_LABEL } from './DefectStatusStepper';
import './DefectActionBoard.css';

interface Props {
  filters: DefectListFilters;
}

// 하자 조치 보드(칸반, HAJA-349/#630) — DefectListPage의 "보드 보기" 탭에서 렌더링된다. 신규 라우트
// 없이 기존 목록 페이지 안에 탭으로 얹는 UI 배치 결정(handoff §UI 배치)에 따라, 라우팅·데이터 조회
// 오케스트레이션은 useDefectActionBoard 훅이 전담하고 이 컴포넌트는 dnd-kit 배선 + 렌더링만 담당한다.
export function DefectActionBoard({ filters }: Props) {
  const {
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
  } = useDefectActionBoard(filters);

  // PointerSensor는 클릭과 드래그 시작을 구분하기 위해 최소 이동거리(distance)를 요구하고,
  // KeyboardSensor는 접근성(키보드만으로 카드를 다른 컬럼에 놓기)을 지원한다(handoff §구현요구사항 2).
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor),
  );

  if (isLoading) {
    return (
      <div className="defect-board__loading" role="status">
        하자 목록을 불러오는 중입니다
      </div>
    );
  }

  if (isError) {
    return <ErrorFallback message="하자 목록을 불러오지 못했습니다." onRetry={refetch} />;
  }

  return (
    <div className="defect-board">
      {dropError && (
        <div className="defect-board__error" role="alert">
          <span>{dropError}</span>
          <button type="button" onClick={clearDropError} aria-label="오류 메시지 닫기">
            ✕
          </button>
        </div>
      )}

      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <div className="defect-board__columns" aria-label="하자 조치 보드">
          {columns.map(({ status, defects }) => (
            <DefectBoardColumn key={status} status={status} label={STEP_LABEL[status]} defects={defects} />
          ))}
        </div>
      </DndContext>

      {reasonRequest && (
        <DefectStatusReasonModal
          defect={reasonRequest.defect}
          targetStatus={reasonRequest.targetStatus}
          onCancel={cancelReasonRequest}
          onSubmit={submitReason}
        />
      )}
    </div>
  );
}
