import { useDroppable } from '@dnd-kit/core';
import type { Defect, DefectStatus } from '../types';
import { DefectBoardCard } from './DefectBoardCard';

interface Props {
  status: DefectStatus;
  label: string;
  defects: Defect[];
}

// 조치 보드 컬럼(HAJA-349/#630) — DefectStatus 5단계(신규/검수확정/조치대기/조치중/조치완료) 중 하나를
// 담당하는 드롭 영역. label은 DefectStatusStepper.STEP_LABEL을 그대로 넘겨받는다(호출부: DefectActionBoard).
export function DefectBoardColumn({ status, label, defects }: Props) {
  const { setNodeRef, isOver } = useDroppable({ id: status });

  return (
    <section
      ref={setNodeRef}
      className={`defect-board-column ${isOver ? 'defect-board-column--over' : ''}`}
      aria-label={`${label} 컬럼`}
    >
      <header className="defect-board-column__header">
        <h3>{label}</h3>
        <span className="defect-board-column__count">{defects.length}</span>
      </header>
      <div className="defect-board-column__body">
        {defects.length === 0 ? (
          <p className="defect-board-column__empty">하자 없음</p>
        ) : (
          defects.map((defect) => <DefectBoardCard key={defect.id} defect={defect} />)
        )}
      </div>
    </section>
  );
}
