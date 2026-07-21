import { Button } from '../../../shared/components/Button/Button';
import { DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';

// 신규→검수확정→조치대기→조치중→조치완료(FR-4) — 백엔드 Defect#changeStatus 와 동일 순서.
// 역행 버튼은 두지 않는다(백엔드가 순서를 강제하지만, UI에서도 막아 불필요한 409를 줄인다).
const STEPS: DefectStatus[] = ['DETECTED', 'CONFIRMED', 'ACTION_PENDING', 'IN_PROGRESS', 'RESOLVED'];

const STEP_LABEL: Record<DefectStatus, string> = {
  DETECTED: '신규',
  CONFIRMED: '검수확정',
  ACTION_PENDING: '조치대기',
  IN_PROGRESS: '조치중',
  RESOLVED: '조치완료',
};

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
    <section className="defect-card defect-status-panel">
      <div className="defect-status-heading"><h2>조치 상태</h2></div>
      <ol className="defect-status-steps" aria-label="하자 조치 상태">
        {STEPS.map((step, index) => {
          const isDone = index < currentIndex;
          const isCurrent = index === currentIndex;
          return (
            <li key={step} className={isCurrent ? 'is-current' : isDone ? 'is-done' : ''}>
              <div>
                <div
                  className="defect-status-marker"
                  aria-current={isCurrent ? 'step' : undefined}
                >
                  {isDone ? '✓' : index + 1}
                </div>
                <span>{STEP_LABEL[step]}</span>
              </div>
            </li>
          );
        })}
      </ol>

      <div className="defect-status-action">
        {errorMessage && (
          <p className="m-0 text-sm text-danger" role="alert">
            {errorMessage}
          </p>
        )}
        <Button
          type="button"
          variant="primary"
          size="lg"
          disabled={nextStatus == null || isPending}
          onClick={() => nextStatus && onAdvance(nextStatus)}
        >
          {nextStatus ? `${DEFECT_STATUS_LABEL[nextStatus]}(으)로 다음 단계` : '조치 완료됨'}
        </Button>
      </div>
    </section>
  );
}
