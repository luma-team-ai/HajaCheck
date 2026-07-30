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
      <LabeledTextArea
        label="종합 의견"
        value={content.summary.overall_opinion}
        readOnly={readOnly}
        rows={3}
        textareaClassName="min-h-20"
        onChange={(value) => updateSummary({ overall_opinion: value })}
      />
    </section>
  );
}
