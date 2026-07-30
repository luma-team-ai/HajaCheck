import { useState } from 'react';
import type { ManualSectionType } from '../../types';
import { MANUAL_SECTION_LABELS } from '../../utils/sectionOrder';

interface AddSectionMenuProps {
  /** 이미 추가된 수동 섹션 타입 — 표준서식은 각 1개씩만 두므로 중복 추가를 막는다. */
  existingTypes: ManualSectionType[];
  onAdd: (type: ManualSectionType) => void;
}

const OPTIONS: ManualSectionType[] = ['submission', 'participants'];

// 백엔드가 만들 수 없는 서식 섹션(제출문·참여기술진 명단)을 편집기에 추가하는 진입점.
export function AddSectionMenu({ existingTypes, onAdd }: AddSectionMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const available = OPTIONS.filter((type) => !existingTypes.includes(type));

  if (available.length === 0) return null;

  return (
    <div className="relative self-start">
      <button
        type="button"
        onClick={() => setIsOpen((prev) => !prev)}
        className="rounded-full border border-dashed border-border px-4 py-2 text-sm font-medium text-text-default transition hover:bg-surface-muted"
      >
        + 서식 섹션 추가
      </button>
      {isOpen && (
        <div className="absolute left-0 top-full z-10 mt-1 flex w-56 flex-col gap-1 rounded-lg border border-border bg-surface p-1.5 shadow-lg">
          {available.map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => {
                onAdd(type);
                setIsOpen(false);
              }}
              className="rounded-md px-3 py-2 text-left text-sm text-text-default transition hover:bg-surface-muted"
            >
              {MANUAL_SECTION_LABELS[type]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
