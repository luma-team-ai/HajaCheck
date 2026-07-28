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
  return 'border-zinc-200 bg-zinc-100 text-zinc-700';
}

const INLINE_INPUT_CLASSES =
  'w-full rounded-lg border border-transparent bg-transparent px-1 py-0.5 text-base font-medium text-zinc-900 outline-none transition focus:border-zinc-300 focus:bg-white focus:ring-2 focus:ring-zinc-100 disabled:cursor-not-allowed read-only:text-zinc-900';

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
      <h2 className="text-xl font-medium leading-7 text-zinc-900">조치 권고</h2>

      {items.length === 0 ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-8 text-center text-sm text-zinc-500">
          조치 권고 항목이 없습니다.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {items.map((item, index) => (
            <article
              key={index}
              className="flex flex-col gap-6 rounded-2xl border border-zinc-200 bg-white p-6"
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <input
                  aria-label={`권고 ${index + 1} 보수 시급성`}
                  className={`max-w-36 rounded-full border px-2.5 py-1 text-xs font-medium outline-none focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed ${priorityPillClass(
                    item.priority,
                  )}`}
                  value={item.priority}
                  disabled={readOnly}
                  onChange={(event) => updateItem(index, { priority: event.target.value })}
                />
                <button
                  type="button"
                  onClick={() => moveToDefect(index)}
                  className="rounded-full bg-zinc-200 px-2.5 py-1 text-xs font-bold text-black transition hover:bg-zinc-300"
                >
                  #DEF-{String(index + 1).padStart(2, '0')} 이동
                </button>
              </div>

              <div className="flex flex-col gap-4">
                <label className="flex flex-col gap-1">
                  <span className="text-xs font-medium tracking-wide text-zinc-700">대상</span>
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
                  textareaClassName="min-h-20 border-transparent bg-transparent px-1 py-1 focus:border-zinc-300 focus:bg-white read-only:bg-transparent read-only:text-zinc-900"
                  onChange={(value) => updateItem(index, { method: value })}
                />
                <LabeledTextArea
                  label={`법적 근거${item.legal_basis_verified ? ' (검증됨)' : ''}`}
                  value={item.legal_basis}
                  readOnly={readOnly}
                  rows={2}
                  textareaClassName="min-h-16 border-transparent bg-transparent px-1 py-1 text-zinc-700 focus:border-zinc-300 focus:bg-white read-only:bg-transparent"
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
        <div className="rounded-2xl border border-zinc-200 bg-white p-5">
          <p className="mb-2 text-xs font-medium tracking-wide text-zinc-700">모니터링 포인트</p>
          <ul className="flex flex-col gap-1 text-sm text-zinc-900">
            {content.recommendation.monitoring_points.map((point, index) => (
              <li key={index}>· {point}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
