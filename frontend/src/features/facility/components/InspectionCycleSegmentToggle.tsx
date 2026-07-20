import { INSPECTION_CYCLE_COLOR_CLASS } from '../inspectionCycleColors';
import type { InspectionCycleType } from '../types';

const OPTIONS: InspectionCycleType[] = ['정기', '정밀', '긴급'];

type Props = {
  value: InspectionCycleType;
  onChange: (value: InspectionCycleType) => void;
};

// 정기/정밀/긴급 세그먼트 — 백엔드 미저장, 로컬 상태 전용(handoff §2)
export function InspectionCycleSegmentToggle({ value, onChange }: Props) {
  return (
    <div
      role="radiogroup"
      aria-label="점검 유형"
      className={`inline-flex w-full gap-1 rounded-full p-1 ${INSPECTION_CYCLE_COLOR_CLASS.segmentTrackBg}`}
    >
      {OPTIONS.map((option) => {
        const isActive = option === value;
        return (
          <button
            key={option}
            type="button"
            role="radio"
            aria-checked={isActive}
            onClick={() => onChange(option)}
            className={`flex-1 rounded-full px-4 py-2 text-sm font-medium transition-colors ${
              isActive
                ? INSPECTION_CYCLE_COLOR_CLASS.segmentActivePill
                : `${INSPECTION_CYCLE_COLOR_CLASS.segmentInactiveText} hover:text-heading`
            }`}
          >
            {option}
          </button>
        );
      })}
    </div>
  );
}
