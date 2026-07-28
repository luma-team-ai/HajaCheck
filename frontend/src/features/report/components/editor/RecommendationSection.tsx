import type { RecommendationItem, ReportContent } from '../../types';
import { Button } from '../../../../shared/components/Button';
import { LabeledTextArea } from './LabeledTextArea';

interface RecommendationSectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

// 시급성 pill 색상 — priority 문자열(한글/영문 모두 대응)에 따라 variant 부여.
function priorityPillClass(priority: string): string {
  const p = priority.trim();
  if (/^(높|상|high|urgent)/i.test(p)) return 'bg-red-100 text-red-700';
  if (/^(보통|중|medium)/i.test(p)) return 'bg-zinc-100 text-zinc-700';
  if (/^(낮|하|low)/i.test(p)) return 'bg-emerald-100 text-emerald-700';
  return 'bg-zinc-100 text-zinc-700';
}

export function RecommendationSection({ content, onChange, readOnly }: RecommendationSectionProps) {
  const items = content.recommendation.items;

  const updateItem = (index: number, patch: Partial<RecommendationItem>) => {
    const next = items.map((it, i) => (i === index ? { ...it, ...patch } : it));
    onChange({ ...content, recommendation: { ...content.recommendation, items: next } });
  };

  const removeItem = (index: number) => {
    onChange({
      ...content,
      recommendation: {
        ...content.recommendation,
        items: items.filter((_, i) => i !== index),
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

  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-zinc-200 bg-white p-8">
      <h2 className="text-lg font-semibold text-text-default">조치 권고</h2>
      {items.length === 0 ? (
        <p className="text-sm text-text-muted">조치 권고 항목이 없습니다.</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {items.map((item, i) => (
            <article
              key={i}
              className="flex flex-col gap-3 rounded-2xl border border-zinc-200 bg-surface-muted p-4"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span
                  className={
                    'rounded-md px-2 py-0.5 text-xs font-semibold ' +
                    priorityPillClass(item.priority)
                  }
                >
                  보수 시급성: {item.priority || '미정'}
                </span>
                <span className="rounded-md border border-border bg-surface px-2 py-0.5 text-xs text-text-muted">
                  #DEF-{String(i + 1).padStart(2, '0')} 이동
                </span>
              </div>
              <label className="flex flex-col gap-1">
                <span className="text-xs font-medium text-text-muted">대상</span>
                <input
                  className="w-full rounded-lg border border-border bg-surface px-2 py-1 text-sm text-text-default disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-text-muted"
                  value={item.target}
                  disabled={readOnly}
                  onChange={(e) => updateItem(i, { target: e.target.value })}
                />
              </label>
              <LabeledTextArea
                label="방법"
                value={item.method}
                readOnly={readOnly}
                rows={2}
                onChange={(v) => updateItem(i, { method: v })}
              />
              <LabeledTextArea
                label={`법적 근거${item.legal_basis_verified ? ' (검증됨)' : ''}`}
                value={item.legal_basis}
                readOnly={readOnly}
                rows={2}
                onChange={(v) => updateItem(i, { legal_basis: v, legal_basis_verified: false })}
              />
              {!readOnly && (
                <Button variant="secondary" size="sm" onClick={() => removeItem(i)}>
                  이 항목 삭제
                </Button>
              )}
            </article>
          ))}
        </div>
      )}
      {!readOnly && (
        <Button variant="secondary" size="sm" onClick={addItem}>
          + 권고 항목 추가
        </Button>
      )}
      {content.recommendation.monitoring_points.length > 0 && (
        <div className="rounded-lg bg-surface-muted p-3">
          <p className="mb-2 text-xs font-medium text-text-muted">모니터링 포인트</p>
          <ul className="flex flex-col gap-1 text-sm text-text-default">
            {content.recommendation.monitoring_points.map((point, i) => (
              <li key={i}>· {point}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
