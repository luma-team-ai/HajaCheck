import type { InspectionCycleType } from '../types';
import { InspectionCycleSegmentToggle } from './InspectionCycleSegmentToggle';
import { InspectionCycleStatusBadge } from './InspectionCycleStatusBadge';
import { InspectionCycleStepper } from './InspectionCycleStepper';
import { InspectionCycleToggleSwitch } from './InspectionCycleToggleSwitch';

type Props = {
  cycleType: InspectionCycleType;
  onCycleTypeChange: (type: InspectionCycleType) => void;
  months: number;
  onMonthsChange: (months: number) => void;
  lastInspectedAt: string;
  nextInspectionDueAt: string | null;
  notifyBeforeEnabled: boolean;
  onNotifyBeforeChange: (checked: boolean) => void;
  warnOnOverdueEnabled: boolean;
  onWarnOnOverdueChange: (checked: boolean) => void;
};

// "이 시설물 주기 설정" 카드 — 순수 프레젠테이셔널(상태·저장 뮤테이션은 페이지가 소유, container/presentational split)
export function InspectionCycleSettingsCard({
  cycleType,
  onCycleTypeChange,
  months,
  onMonthsChange,
  lastInspectedAt,
  nextInspectionDueAt,
  notifyBeforeEnabled,
  onNotifyBeforeChange,
  warnOnOverdueEnabled,
  onWarnOnOverdueChange,
}: Props) {
  return (
    <section className="flex flex-col gap-6 rounded-2xl border border-border bg-surface p-6">
      <h2 className="m-0 flex items-center gap-2 text-base font-bold text-heading">
        <span aria-hidden="true">⚙</span> 이 시설물 주기 설정
      </h2>

      <InspectionCycleSegmentToggle value={cycleType} onChange={onCycleTypeChange} />

      <div className="flex flex-col gap-2">
        <span className="text-sm font-medium text-text-default">주기 입력</span>
        <InspectionCycleStepper months={months} onChange={onMonthsChange} />
      </div>

      <div className="flex flex-col gap-3 rounded-xl border border-border p-4">
        <div className="flex items-center justify-between">
          <span className="text-sm text-text-muted">마지막 점검일</span>
          <span className="rounded-full border border-border px-3 py-1 text-sm text-text-default">
            {lastInspectedAt}
          </span>
        </div>
        <hr className="m-0 border-t border-border" />
        <div className="flex items-center justify-between">
          <span className="text-sm text-text-muted">다음 점검일</span>
          <div className="flex items-center gap-2">
            <span className="text-2xl font-bold text-heading">{nextInspectionDueAt ?? '미정'}</span>
            <InspectionCycleStatusBadge nextInspectionDueAt={nextInspectionDueAt} />
          </div>
        </div>
      </div>

      <div className="flex flex-col gap-3">
        <InspectionCycleToggleSwitch
          label="점검일 도래 7일 전 알림"
          checked={notifyBeforeEnabled}
          onChange={onNotifyBeforeChange}
        />
        <InspectionCycleToggleSwitch
          label="기한 초과 시 관리자에게 경고"
          checked={warnOnOverdueEnabled}
          onChange={onWarnOnOverdueChange}
        />
      </div>
    </section>
  );
}
