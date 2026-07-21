import { INSPECTION_CYCLE_COLOR_CLASS } from '../inspectionCycleColors';

type Props = {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
};

// 알림 설정 토글(도래 7일 전 알림 / 기한 초과 경고) — 백엔드 미저장, 목 전용(handoff §2)
export function InspectionCycleToggleSwitch({ label, checked, onChange }: Props) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-sm text-text-default">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
          checked ? INSPECTION_CYCLE_COLOR_CLASS.toggleOnTrackBg : INSPECTION_CYCLE_COLOR_CLASS.toggleOffTrackBg
        }`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full transition-transform ${INSPECTION_CYCLE_COLOR_CLASS.toggleThumbBg} ${
            checked ? 'translate-x-6' : 'translate-x-1'
          }`}
        />
      </button>
    </div>
  );
}
