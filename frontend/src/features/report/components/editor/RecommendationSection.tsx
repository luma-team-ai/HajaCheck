import type { RecommendationItem, ReportContent } from '../../types';
import { Button } from '../../../../shared/components/Button';
import { LabeledTextArea } from './LabeledTextArea';

interface RecommendationSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

function priorityPillClass(priority: string): string {
  const normalized = priority.trim();
  if (/^(높|상|high|urgent)/i.test(normalized)) {
    return 'border-red-200 bg-red-50 text-red-700';
  }
  if (/^(낮|하|low)/i.test(normalized)) {
    return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  }
  return 'border-border bg-surface-muted text-text-default';
}

function formatPriorityLabel(priority: string): string {
  const normalized = priority.trim();
  if (/^(높|상|고|high|urgent)/i.test(normalized)) return '보수 시급성: 고';
  if (/^(낮|하|저|low)/i.test(normalized)) return '보수 시급성: 저';
  if (/^(중|보통|medium|normal)/i.test(normalized)) return '보수 시급성: 중';
  return normalized ? `보수 시급성: ${normalized}` : '보수 시급성: 미정';
}

const INLINE_INPUT_CLASSES =
  'w-full rounded-lg border border-transparent bg-transparent px-0 py-0 text-base font-medium leading-6 text-heading outline-none transition focus:border-primary focus:bg-surface focus:px-2 focus:py-1 focus:ring-2 focus:ring-primary/10 disabled:cursor-not-allowed read-only:text-heading';

const INLINE_TEXTAREA_CLASS =
  'min-h-20 border-transparent bg-transparent px-0 py-0 leading-6 focus:border-primary focus:bg-surface focus:px-2 focus:py-1 read-only:bg-transparent read-only:text-heading';

export function RecommendationSection({
  content,
  onChange,
  readOnly,
}: RecommendationSectionProps) {
  const items = content.recommendation.items;

  const updateItem = (index: number, patch: Partial<RecommendationItem>) => {
    const next = items.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...patch } : item,
    );
    onChange({
      ...content,
      recommendation: { ...content.recommendation, items: next },
    });
  };

  const removeItem = (index: number) => {
    onChange({
      ...content,
      recommendation: {
        ...content.recommendation,
        items: items.filter((_, itemIndex) => itemIndex !== index),
      },
    });
  };

  const addItem = () => {
    const blank: RecommendationItem = {
      target: '',
      method: '',
      priority: '',
      legal_basis: '',
      legal_basis_verified: false,
    };
    onChange({
      ...content,
      recommendation: { ...content.recommendation, items: [...items, blank] },
    });
  };

  const moveToDefect = (index: number) => {
    document
      .getElementById(`report-defect-${index + 1}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return (
    <section className="flex flex-col gap-6">
      <h2 className="text-xl font-medium leading-7 text-heading">조치 권고</h2>

      {items.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface p-8 text-center text-sm text-text-muted">
          조치 권고 항목이 없습니다.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {items.map((item, index) => (
            <article key={index} className="flex flex-col gap-5 rounded-lg border border-border bg-surface p-5">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <input
                  aria-label={`권고 ${index + 1} 보수 시급성`}
                  className={`w-32 rounded-full border px-2.5 py-1 text-xs font-medium outline-none focus:ring-2 focus:ring-primary/10 disabled:cursor-not-allowed ${priorityPillClass(
                    item.priority,
                  )}`}
                  value={formatPriorityLabel(item.priority)}
                  disabled={readOnly}
                  onChange={(event) =>
                    updateItem(index, { priority: event.target.value.replace(/^보수 시급성:\s*/, '') })
                  }
                />
                <button
                  type="button"
                  onClick={() => moveToDefect(index)}
                  className="rounded-lg bg-text-default px-2.5 py-1 text-xs font-bold text-surface transition hover:bg-heading"
                >
                  DEFECT #{String(index + 1).padStart(2, '0')}
                </button>
              </div>

              <div className="grid gap-4">
                <label className="grid gap-2 sm:grid-cols-[88px_minmax(0,1fr)] sm:items-start">
                  <span className="text-xs font-medium tracking-wide text-text-default">대상</span>
                  <input
                    className={INLINE_INPUT_CLASSES}
                    value={item.target}
                    disabled={readOnly}
                    onChange={(event) => updateItem(index, { target: event.target.value })}
                  />
                </label>
                <LabeledTextArea
                  label="방법"
                  value={item.method}
                  readOnly={readOnly}
                  rows={3}
                  className="grid gap-2 sm:grid-cols-[88px_minmax(0,1fr)] sm:items-start"
                  textareaClassName={INLINE_TEXTAREA_CLASS}
                  onChange={(value) => updateItem(index, { method: value })}
                />
                <LabeledTextArea
                  label={`법적 근거${item.legal_basis_verified ? ' (검증됨)' : ''}`}
                  value={item.legal_basis}
                  readOnly={readOnly}
                  rows={2}
                  className="grid gap-2 sm:grid-cols-[88px_minmax(0,1fr)] sm:items-start"
                  textareaClassName={`${INLINE_TEXTAREA_CLASS} min-h-16 text-text-default`}
                  onChange={(value) =>
                    updateItem(index, { legal_basis: value, legal_basis_verified: false })
                  }
                />
              </div>

              {!readOnly && (
                <div className="flex justify-end">
                  <Button variant="secondary" size="sm" onClick={() => removeItem(index)}>
                    이 항목 삭제
                  </Button>
                </div>
              )}
            </article>
          ))}
        </div>
      )}

      {!readOnly && (
        <div>
          <Button variant="secondary" size="sm" onClick={addItem}>
            + 권고 항목 추가
          </Button>
        </div>
      )}

      {content.recommendation.monitoring_points.length > 0 && (
        <div className="rounded-lg border border-border bg-surface p-5">
          <p className="mb-2 text-xs font-medium tracking-wide text-text-default">모니터링 포인트</p>
          <ul className="flex flex-col gap-1 text-sm text-heading">
            {content.recommendation.monitoring_points.map((point, index) => (
              <li key={index}>· {point}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
