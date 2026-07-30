import { useState, type ReactNode } from 'react';

interface DraggableSectionSlotProps {
  index: number;
  label: string;
  removable: boolean;
  readOnly: boolean;
  onReorder: (fromIndex: number, toIndex: number) => void;
  onRemove?: () => void;
  children: ReactNode;
}

// 자소서 편집기(사람인 등) 스타일 — 각 섹션 카드 위에 드래그 손잡이 바를 얹어 순서를 바꾼다.
// 기존 섹션 컴포넌트(OverviewSection 등)는 이 컴포넌트가 감쌀 뿐 내부를 전혀 건드리지 않는다
// (그 컴포넌트들은 이미 자기 카드 테두리·헤더를 그리므로, 이 래퍼는 얇은 손잡이 바만 추가).
// 새 의존성을 넣지 않기 위해 dnd-kit 대신 네이티브 HTML5 Drag and Drop을 쓴다(이 화면은 데스크톱
// 편집 전용이라 마우스 드래그만 지원해도 충분하다는 판단).
export function DraggableSectionSlot({
  index,
  label,
  removable,
  readOnly,
  onReorder,
  onRemove,
  children,
}: DraggableSectionSlotProps) {
  const [isDragOver, setIsDragOver] = useState(false);

  // readOnly 전환 시에도 항상 같은 트리 모양(같은 자식 개수·순서)을 유지한다 — 자식 수가
  // 바뀌면 React가 위치 기반으로 재조정하다 자식 컴포넌트를 통째로 리마운트해 그 안의
  // ref·포커스 상태(예: LabeledTextArea의 textarea)가 날아간다("저장 중" 동안 readOnly가
  // 잠깐 true였다 false로 돌아오는 흐름에서 실제로 재현된 회귀). 드래그 이벤트 핸들러만
  // readOnly일 때 no-op으로 바꾸고, 손잡이 바는 항상 렌더링한다.
  return (
    <div
      onDragOver={(event) => {
        if (readOnly) return;
        event.preventDefault();
        setIsDragOver(true);
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={(event) => {
        if (readOnly) return;
        event.preventDefault();
        setIsDragOver(false);
        const fromIndex = Number(event.dataTransfer.getData('text/plain'));
        if (!Number.isNaN(fromIndex)) onReorder(fromIndex, index);
      }}
      className={`flex flex-col gap-1.5 rounded-lg transition ${isDragOver ? 'ring-2 ring-primary/40' : ''}`}
    >
      <div className="flex items-center justify-between px-1 text-xs text-text-muted">
        <div
          draggable={!readOnly}
          onDragStart={(event) => {
            if (readOnly) return;
            event.dataTransfer.setData('text/plain', String(index));
            event.dataTransfer.effectAllowed = 'move';
          }}
          className={`flex items-center gap-1.5 select-none ${readOnly ? '' : 'cursor-grab active:cursor-grabbing'}`}
          aria-label={`${label} 섹션${readOnly ? '' : ' 드래그로 순서 변경'}`}
        >
          <svg className="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="5" cy="4" r="1" fill="currentColor" />
            <circle cx="5" cy="8" r="1" fill="currentColor" />
            <circle cx="5" cy="12" r="1" fill="currentColor" />
            <circle cx="11" cy="4" r="1" fill="currentColor" />
            <circle cx="11" cy="8" r="1" fill="currentColor" />
            <circle cx="11" cy="12" r="1" fill="currentColor" />
          </svg>
          <span className="font-medium tracking-wide">{label}</span>
        </div>
        {removable && !readOnly && onRemove && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded-full px-2 py-0.5 transition hover:bg-surface-muted hover:text-red-600"
          >
            섹션 삭제
          </button>
        )}
      </div>
      {children}
    </div>
  );
}
