import type { CSSProperties } from 'react';
import { useDraggable } from '@dnd-kit/core';
import type { Defect } from '../types';
import { GRADE_CLASSES } from './DefectTable';

interface Props {
  defect: Defect;
}

// 조치 보드 카드(HAJA-349/#630) — 유형/등급/시설물명/썸네일 요약만 노출한다(handoff §구현요구사항 3).
// 등급 배지 색상은 DefectTable.GRADE_CLASSES를 그대로 재사용(신규 색상 상수 추가 금지 컨벤션).
export function DefectBoardCard({ defect }: Props) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: defect.id,
  });

  const style: CSSProperties = {
    transform: transform ? `translate3d(${transform.x}px, ${transform.y}px, 0)` : undefined,
  };

  return (
    <article
      ref={setNodeRef}
      style={style}
      className={`defect-board-card ${isDragging ? 'defect-board-card--dragging' : ''}`}
      aria-label={`${defect.typeLabel} 하자 카드, ${defect.facilityName}`}
      {...listeners}
      {...attributes}
    >
      {defect.imageUrl ? (
        <img src={defect.imageUrl} alt="" className="defect-board-card__thumb" />
      ) : (
        <div className="defect-board-card__thumb defect-board-card__thumb--empty" aria-hidden="true">
          {defect.typeLabel.slice(0, 1)}
        </div>
      )}
      <div className="defect-board-card__body">
        <div className="defect-board-card__meta">
          <span className="defect-board-card__type">{defect.typeLabel}</span>
          {defect.grade && (
            <span className={`defect-board-card__grade ${GRADE_CLASSES[defect.grade]}`}>{defect.grade}</span>
          )}
        </div>
        <p className="defect-board-card__facility">{defect.facilityName}</p>
      </div>
    </article>
  );
}
