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
    <section className="flex flex-col gap-6">
      <div className="rounded-lg border border-border bg-surface px-5 py-4">
        <LabeledTextArea
          label="종합 의견"
          hideLabel
          value={content.summary.overall_opinion}
          readOnly={readOnly}
          rows={3}
          textareaClassName="min-h-20 border-0 bg-transparent p-0 shadow-none focus:border-transparent focus:ring-0 read-only:bg-transparent"
          onChange={(value) => updateSummary({ overall_opinion: value })}
        />
        <div className="mt-3 inline-flex items-center gap-2 text-xs font-medium text-point">
          AI 요약 생성됨
        </div>
      </div>
    </section>
  );
}
