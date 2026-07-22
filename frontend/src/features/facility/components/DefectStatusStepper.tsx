import { FACILITY_DEFECT_STATUS_LABEL, FACILITY_DEFECT_STATUS_ORDER } from '../constants';
import type { FacilityDefectStatus } from '../types';

type Props = {
  status: FacilityDefectStatus;
};

// 하자 조치 상태 스테퍼(신규→검수확정→조치대기→조치중→조치완료) — dev-04-02, #489.
// facility/components/InspectionCycleStepper.tsx(개월수 +/- 입력, 전혀 다른 용도)와 이름 충돌 주의.
export function DefectStatusStepper({ status }: Props) {
  const currentIndex = FACILITY_DEFECT_STATUS_ORDER.indexOf(status);

  return (
    <ol className="relative m-0 flex list-none items-start justify-between p-0" aria-label="하자 조치 상태">
      <li className="absolute left-0 right-0 top-1.5 -z-0 h-0.5 bg-[#e4e4e7]" aria-hidden="true" />
      {FACILITY_DEFECT_STATUS_ORDER.map((step, index) => {
        const isCompleted = index < currentIndex;
        const isCurrent = index === currentIndex;
        const dotClass = isCompleted || isCurrent ? 'bg-heading' : 'bg-[#e4e4e7]';
        const ringClass = isCurrent ? 'ring-2 ring-offset-2 ring-heading' : '';
        const labelClass = isCurrent ? 'font-bold text-heading' : 'text-text-muted';

        return (
          <li key={step} className="relative z-10 flex flex-1 flex-col items-center gap-1.5">
            <span
              aria-current={isCurrent ? 'step' : undefined}
              className={`h-3 w-3 rounded-full ${dotClass} ${ringClass}`}
            />
            <span className={`text-xs ${labelClass}`}>{FACILITY_DEFECT_STATUS_LABEL[step]}</span>
          </li>
        );
      })}
    </ol>
  );
}