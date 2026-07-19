import { Button } from '../../../shared/components/Button/Button';
import { DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';

// 신규→검수확정→조치대기→조치중→조치완료(FR-4) — 백엔드 Defect#changeStatus 와 동일 순서.
// 역행 버튼은 두지 않는다(백엔드가 순서를 강제하지만, UI에서도 막아 불필요한 409를 줄인다).
const STEPS: DefectStatus[] = ['DETECTED', 'CONFIRMED', 'ACTION_PENDING', 'IN_PROGRESS', 'RESOLVED'];

const NEXT_STATUS: Record<DefectStatus, DefectStatus | null> = {
  DETECTED: 'CONFIRMED',
  CONFIRMED: 'ACTION_PENDING',
  ACTION_PENDING: 'IN_PROGRESS',
  IN_PROGRESS: 'RESOLVED',
  RESOLVED: null,
};

interface DefectStatusStepperProps {
  status: DefectStatus;
  onAdvance: (next: DefectStatus) => void;
  isPending?: boolean;
  errorMessage?: string;
}

export function DefectStatusStepper({
  status,
  onAdvance,
  isPending = false,
  errorMessage,
}: DefectStatusStepperProps) {
  const currentIndex = STEPS.indexOf(status);
  const nextStatus = NEXT_STATUS[status];

  return (
    <div className="mb-6 rounded-2xl border border-border bg-surface p-6">
      <ol className="m-0 flex list-none items-center gap-2 p-0" aria-label="하자 조치 상태">
        {STEPS.map((step, index) => {
          const isDone = index < currentIndex;
          const isCurrent = index === currentIndex;
          return (
            <li key={step} className="flex flex-1 items-center gap-2 last:flex-none">
              <div className="flex flex-1 flex-col items-center gap-1.5">
                <div
                  className={`flex h-7 w-7 items-center justify-center rounded-full text-xs font-semibold ${
                    isCurrent
                      ? 'bg-primary text-surface'
                      : isDone
                        ? 'bg-primary/20 text-primary'
                        : 'bg-surface-muted text-text-muted'
                  }`}
                  aria-current={isCurrent ? 'step' : undefined}
                >
                  {index + 1}
                </div>
                <span
                  className={`text-xs font-medium ${
                    isCurrent ? 'text-text-default' : 'text-text-muted'
                  }`}
                >
                  {DEFECT_STATUS_LABEL[step]}
                </span>
              </div>
              {index < STEPS.length - 1 && (
                <div
                  className={`h-px flex-1 ${index < currentIndex ? 'bg-primary/40' : 'bg-border'}`}
                  aria-hidden="true"
                />
              )}
            </li>
          );
        })}
      </ol>

      <div className="mt-5 flex items-center justify-end gap-3">
        {errorMessage && (
          <p className="m-0 text-sm text-danger" role="alert">
            {errorMessage}
          </p>
        )}
        <Button
          type="button"
          variant="primary"
          size="md"
          disabled={nextStatus == null || isPending}
          onClick={() => nextStatus && onAdvance(nextStatus)}
        >
          {nextStatus ? `${DEFECT_STATUS_LABEL[nextStatus]}(으)로 다음 단계` : '조치 완료됨'}
        </Button>
      </div>
    </div>
  );
}
