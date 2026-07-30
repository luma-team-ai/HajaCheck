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

// 각 섹션의 공통 카드 골격과 제목 행을 제공한다. 내부 섹션은 자기 제목/외곽선을 다시 그리지 않는다.
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
      className={`flex flex-col gap-6 rounded-lg border border-border bg-surface p-6 transition sm:p-8 ${
        isDragOver ? 'ring-2 ring-primary/40' : ''
      }`}
    >
      <div className="flex items-center justify-between gap-4">
        <div
          draggable={!readOnly}
          onDragStart={(event) => {
            if (readOnly) return;
            event.dataTransfer.setData('text/plain', String(index));
            event.dataTransfer.effectAllowed = 'move';
          }}
          className={`flex min-w-0 items-center gap-2 select-none text-xl font-medium leading-7 text-heading ${
            readOnly ? '' : 'cursor-grab active:cursor-grabbing'
          }`}
          aria-label={`${label} 섹션${readOnly ? '' : ' 드래그로 순서 변경'}`}
        >
          <svg className="h-4 w-4 shrink-0 text-text-muted" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="5" cy="4" r="1" fill="currentColor" />
            <circle cx="5" cy="8" r="1" fill="currentColor" />
            <circle cx="5" cy="12" r="1" fill="currentColor" />
            <circle cx="11" cy="4" r="1" fill="currentColor" />
            <circle cx="11" cy="8" r="1" fill="currentColor" />
            <circle cx="11" cy="12" r="1" fill="currentColor" />
          </svg>
          <span className="truncate">{label}</span>
        </div>
        {removable && !readOnly && onRemove && (
          <button
            type="button"
            onClick={onRemove}
            className="shrink-0 rounded-full border border-border px-3 py-1.5 text-xs font-medium text-text-muted transition hover:border-red-200 hover:bg-red-50 hover:text-red-600"
          >
            섹션 삭제
          </button>
        )}
      </div>
      {children}
    </div>
  );
}
