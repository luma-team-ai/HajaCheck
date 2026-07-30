import { Button } from '../../../../shared/components/Button';
import { AI_DRAFT_WARNING, AI_DRAFT_WARNING_TITLE } from '../../constants';

export interface ReportStepView {
  key: string;
  label: string;
  active: boolean;
}

interface ReportEditorHeroProps {
  createdAt: string;
  isFinalized: boolean;
  progressPercent: number;
  reviewedCount?: number;
  totalCount?: number;
  defectCount: number;
  steps: ReportStepView[];
  canFinalize: boolean;
  isFinalizing: boolean;
  /** 진행 단계별 버튼 라벨(예: "저장 중...")을 부모가 넘겨줄 때 기본 라벨 대신 사용한다. */
  finalizeLabel?: string;
  onFinalize: () => void;
  /** "PDF 미리보기" 클릭 핸들러 — 미저장 변경분이 있으면 임시저장 후 이동하는 가드는
   * 부모(ReportGeneratePage)가 처리한다(#1338). */
  onPreviewClick: () => void;
  canSave: boolean;
  isSaving: boolean;
  onSaveClick: () => void;
}

function formatDateParts(iso: string) {
  const date = new Date(iso);
  return {
    date: new Intl.DateTimeFormat('ko-KR', {
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
    }).format(date),
    time: new Intl.DateTimeFormat('ko-KR', {
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
    }).format(date),
  };
}

function PaperPlaneIcon() {
  return (
    <svg className="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M21.5 3.5 10.6 14.4M21.5 3.5 14.7 21l-4.1-6.6-7.1-3.2 18-7.7Z"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function ReportEditorHero({
  createdAt,
  isFinalized,
  progressPercent,
  reviewedCount,
  totalCount,
  defectCount,
  steps,
  canFinalize,
  isFinalizing,
  finalizeLabel,
  onFinalize,
  onPreviewClick,
  canSave,
  isSaving,
  onSaveClick,
}: ReportEditorHeroProps) {
  const created = formatDateParts(createdAt);
  const currentStepIndex = steps.reduce(
    (lastActiveIndex, step, index) => (step.active ? index : lastActiveIndex),
    0,
  );

  return (
    <div className="flex flex-col gap-6">
      <h1 className="sr-only">보고서 생성 결과</h1>

      <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
        <div className="flex min-h-24 w-full max-w-[660px] items-start gap-4 rounded-lg border border-warning-soft-border bg-warning-soft-bg p-4 text-warning-soft-fg xl:flex-none">
          <svg className="mt-0.5 h-5 w-5 shrink-0 text-warning-soft-fg" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M10 2.4 18 17H2L10 2.4Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
            <path d="M10 7v4.2M10 14.2v.1" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          <div className="text-sm leading-6">
            <p className="font-medium">{AI_DRAFT_WARNING_TITLE}</p>
            <p className="mt-0.5 whitespace-pre-line text-warning-soft-fg">{AI_DRAFT_WARNING}</p>
          </div>
        </div>

        <div className="flex shrink-0 flex-wrap items-center gap-2 xl:justify-end">
          <Button onClick={onSaveClick} variant="secondary" size="md" disabled={!canSave || isSaving}>
            {isSaving ? '저장 중...' : '임시저장'}
          </Button>
          <button
            type="button"
            onClick={onPreviewClick}
            className="inline-flex items-center justify-center rounded-full border border-border bg-surface px-6 py-2 text-xs font-medium text-heading no-underline transition hover:bg-surface-muted"
          >
            PDF 미리보기
          </button>
          <Button
            onClick={onFinalize}
            variant="primary"
            size="md"
            disabled={!canFinalize || isFinalizing}
            className="min-w-[168px] gap-2 bg-primary px-5 text-xs text-surface"
          >
            {finalizeLabel ?? (isFinalizing ? 'PDF 생성/확정 중...' : '최종 보고서 확정')}
            <PaperPlaneIcon />
          </Button>
        </div>
      </div>

      <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div className="flex min-h-[120px] flex-col justify-between rounded-lg border border-border bg-surface p-6">
          <dt className="text-xs font-medium tracking-wide text-text-default">현재 상태</dt>
          <dd className="m-0 flex items-center justify-between">
            <div>
              <p className="text-3xl font-bold leading-9 text-heading">
                {isFinalized ? '확정' : '검수 중'}
              </p>
              <p className="mt-1 text-xs text-text-default">
                {isFinalized ? '최종 확정 완료' : '초안 제출 완료 (임시 저장)'}
              </p>
            </div>
            <span
              className={`h-3 w-3 rounded-full ${isFinalized ? 'bg-emerald-500' : 'bg-amber-500'}`}
              aria-hidden="true"
            />
          </dd>
        </div>

        <div className="flex min-h-[120px] flex-col gap-2 rounded-lg border border-border bg-surface p-6">
          <dt className="text-xs font-medium tracking-wide text-text-default">생성일시</dt>
          <dd className="m-0 text-base font-bold leading-6 text-heading">
            <span className="block">{created.date}</span>
            <span className="block">{created.time}</span>
          </dd>
        </div>

        <div className="flex min-h-[120px] flex-col gap-2 rounded-lg border border-border bg-surface p-6">
          <dt className="text-xs font-medium tracking-wide text-text-default">검수 완료율</dt>
          <dd className="m-0 text-3xl font-bold leading-9 text-heading">
            {progressPercent.toFixed(0)}%
          </dd>
          {typeof reviewedCount === 'number' && typeof totalCount === 'number' && (
            <span className="sr-only">
              {reviewedCount} / {totalCount}
            </span>
          )}
        </div>

        <div className="flex min-h-[120px] flex-col gap-2 rounded-lg border border-border bg-surface p-6">
          <dt className="text-xs font-medium tracking-wide text-text-default">총 지적 수</dt>
          <dd className="m-0 flex items-baseline gap-1">
            <span className="text-3xl font-bold leading-9 text-red-500">{defectCount}</span>
            <span className="text-sm text-text-default">건</span>
          </dd>
        </div>
      </dl>

      <section className="rounded-lg border border-border bg-surface px-4 py-8 sm:px-8 sm:pb-10">
        <ol className="relative grid grid-cols-3" aria-label="보고서 작성 단계">
          <div
            className="absolute left-[16.7%] right-[16.7%] top-5 h-px bg-border"
            aria-hidden="true"
          />
          {steps.map((step, index) => (
            <li
              key={step.key}
              className="relative z-10 flex min-w-0 flex-col items-center gap-2 text-center"
            >
              <span
                className={`inline-flex h-10 w-10 items-center justify-center rounded-full text-base font-bold ${
                  step.active
                    ? 'bg-primary text-surface'
                    : 'border-2 border-border bg-surface text-text-default'
                }`}
                aria-current={index === currentStepIndex ? 'step' : undefined}
              >
                {step.key}
              </span>
              <span
                className={`truncate text-[11px] font-medium tracking-wide text-heading sm:text-xs ${
                  step.active ? 'opacity-100' : 'opacity-30'
                }`}
              >
                {step.label}
              </span>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
