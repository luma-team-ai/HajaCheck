import type { ReportContent } from '../../types';
import { LabeledTextArea } from './LabeledTextArea';

interface SummarySectionProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

export function SummarySection({ content, onChange, readOnly }: SummarySectionProps) {
  const updateSummary = (patch: Partial<ReportContent['summary']>) =>
    onChange({ ...content, summary: { ...content.summary, ...patch } });

  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-zinc-200 bg-white p-8">
      <h2 className="text-lg font-semibold text-text-default">요약 결론</h2>
      <LabeledTextArea
        label="종합 의견"
        value={content.summary.overall_opinion}
        readOnly={readOnly}
        onChange={(v) => updateSummary({ overall_opinion: v })}
      />
      <div className="flex flex-wrap items-center gap-3 rounded-lg bg-surface-muted p-3 text-xs text-text-muted">
        <span className="inline-flex items-center gap-1 rounded-full bg-info-soft-bg px-2 py-0.5 font-medium text-info-soft-fg">
          ✨ AI 요약 생성됨
        </span>
        <span>
          총 {content.summary.total_count}건 ·{' '}
          {Object.entries(content.summary.count_by_grade)
            .map(([grade, count]) => `${grade}등급 ${count}건`)
            .join(', ')}
        </span>
      </div>
    </section>
  );
}
